#!/usr/bin/env python3
"""
Разбирает трейс Perfetto в CSV и сводку.

    python3 scripts/perfetto_analyze.py <трейс>.pb
    python3 scripts/perfetto_analyze.py <старый>.pb <новый>.pb
    python3 scripts/perfetto_analyze.py <трейс>.pb -o /tmp/out

Нужен пакет perfetto, его ставят в venv. Всё это делает analyze_perfetto.sh.

    python3 -m venv .venv
    .venv/bin/pip install perfetto

На каждый трейс пишет каталог из пяти файлов.
    summary.txt      сводка по кадрам, секциям, рекомпозициям и главному потоку
    frames.csv       кадры SurfaceFlinger, по строке на кадр
    slices.csv       секции приложения updateTiles, flushPendingTiles, decodeTile
    recompose.csv    слайсы рекомпозиции Compose
    slice_names.txt  все имена слайсов процесса, чтобы проверять сами запросы

Два трейса дают ещё и comparison.txt.

Секции приложения и рекомпозиции появляются в трейсе только с категориями atrace app
и view, поэтому трейс без них даст пустые slices.csv и recompose.csv.
"""

import argparse
import csv
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

APP_PACKAGE = "com.shevikina.panzoomdemo"

# ── SQL ────────────────────────────────────────────────────────────────────────
# Процесс ищется по LIKE, а не по полному имени, потому что в трейсе оно бывает
# обрезано слева до последних символов.

_SQL_T0 = f"""
    SELECT MIN(s.ts) AS t0
    FROM slice s
    JOIN thread_track tt ON s.track_id = tt.id
    JOIN thread t ON tt.utid = t.utid
    JOIN process p ON t.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
"""

_SQL_DURATION = f"""
    SELECT (MAX(s.ts + s.dur) - MIN(s.ts)) / 1e9 AS dur_sec
    FROM slice s
    JOIN thread_track tt ON s.track_id = tt.id
    JOIN thread t ON tt.utid = t.utid
    JOIN process p ON t.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
"""

_SQL_APP_SLICES = f"""
    SELECT s.ts, s.dur, s.name, t.name AS thread_name
    FROM slice s
    JOIN thread_track tt ON s.track_id = tt.id
    JOIN thread t ON tt.utid = t.utid
    JOIN process p ON t.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
      AND s.name IN ('updateTiles', 'flushPendingTiles', 'decodeTile.wait', 'decodeTile.decode')
    ORDER BY s.ts
"""

# Кадры берём из таблицы SurfaceFlinger, а не по Choreographer#doFrame, потому что
# она учитывает и время GPU, и сама размечает вид джанка.
#
# Пустой jank_type означает гладкий кадр. 'App Deadline Missed' это настоящий джанк,
# а 'Buffer Stuffing' идёт вместе с on_time_finish=1 и дропом кадра не является.
_SQL_FRAMES = f"""
    SELECT s.ts, s.dur / 1e6 AS dur_ms, s.jank_type, s.on_time_finish
    FROM actual_frame_timeline_slice s
    JOIN process p ON s.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
    ORDER BY s.ts
"""

_SQL_RECOMPOSE = f"""
    SELECT s.ts, s.dur, s.name
    FROM slice s
    JOIN thread_track tt ON s.track_id = tt.id
    JOIN thread t ON tt.utid = t.utid
    JOIN process p ON t.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
      AND (s.name LIKE '%ecompose%' OR s.name LIKE '%Recomposition%')
    ORDER BY s.ts
"""

_SQL_MAIN_TOP = f"""
    SELECT
      CASE WHEN s.name LIKE 'Choreographer#doFrame%' THEN 'Choreographer#doFrame'
           WHEN s.name LIKE 'Choreographer#onVsync%' THEN 'Choreographer#onVsync'
           WHEN s.name LIKE 'deliverInputEvent%'    THEN 'deliverInputEvent'
           ELSE s.name END AS norm_name,
      COUNT(*) AS cnt,
      ROUND(AVG(s.dur) / 1e6, 2) AS avg_ms,
      ROUND(MAX(s.dur) / 1e6, 2) AS max_ms,
      ROUND(SUM(s.dur) / 1e6,  1) AS total_ms
    FROM slice s
    JOIN thread_track tt ON s.track_id = tt.id
    JOIN thread t ON tt.utid = t.utid
    JOIN process p ON t.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
      AND t.name LIKE '%panzoomdemo%'
      AND s.depth = 0
    GROUP BY norm_name
    ORDER BY total_ms DESC
    LIMIT 20
"""

# Все имена слайсов процесса. Нужны, когда ожидаемых слайсов в трейсе не нашлось
# и надо понять, чем они называются на самом деле.
_SQL_ALL_NAMES = f"""
    SELECT s.name, t.name AS thread_name, COUNT(*) AS cnt
    FROM slice s
    JOIN thread_track tt ON s.track_id = tt.id
    JOIN thread t ON tt.utid = t.utid
    JOIN process p ON t.upid = p.id
    WHERE p.name LIKE '%panzoomdemo%'
    GROUP BY s.name, t.name
    ORDER BY cnt DESC
    LIMIT 50
"""

# ── Dataclasses ───────────────────────────────────────────────────────────────

@dataclass
class AppSlice:
    rel_ms: float
    dur_ms: float
    name: str
    thread: str

@dataclass
class FrameRow:
    rel_ms: float
    dur_ms: float
    jank_type: Optional[str]   # None, 'App Deadline Missed', 'Buffer Stuffing' и прочие
    on_time: int               # 1 кадр доставлен вовремя, 0 с опозданием

@dataclass
class RecomposeRow:
    rel_ms: float
    dur_ms: float
    name: str

@dataclass
class MainTopEntry:
    name: str
    count: int
    avg_ms: float
    max_ms: float
    total_ms: float

@dataclass
class SliceNameEntry:
    name: str
    thread: str
    count: int

@dataclass
class TraceSession:
    name: str
    duration_sec: float
    app_slices: list = field(default_factory=list)   # list[AppSlice]
    frames: list = field(default_factory=list)        # list[FrameRow]
    recompose: list = field(default_factory=list)     # list[RecomposeRow]
    main_top: list = field(default_factory=list)      # list[MainTopEntry]
    all_names: list = field(default_factory=list)     # list[SliceNameEntry]

# ── Анализ ────────────────────────────────────────────────────────────────────

def _import_tp():
    """Класс TraceProcessor из пакета perfetto. Обрывает работу, если пакета нет."""
    try:
        from perfetto.trace_processor import TraceProcessor
        return TraceProcessor
    except ImportError:
        print(
            "\nОшибка: пакет 'perfetto' не найден.\n"
            "Поставь его в venv.\n"
            "  python3 -m venv .venv\n"
            "  source .venv/bin/activate\n"
            "  pip install perfetto\n",
            file=sys.stderr,
        )
        sys.exit(1)


def analyze(path: Path) -> TraceSession:
    """Собирает из трейса всё, что нужно отчётам, одним набором запросов.

    Времена в результате отсчитываются от первого слайса процесса, а не от начала
    трейса, потому что трейс начинается раньше, чем приложение попадает в кадр.
    """
    TraceProcessor = _import_tp()
    tp = TraceProcessor(trace=str(path))

    try:
        t0_ns: int = 0
        for row in tp.query(_SQL_T0):
            t0_ns = int(row.t0 or 0)
            break

        dur_sec: float = 0.0
        for row in tp.query(_SQL_DURATION):
            dur_sec = round(float(row.dur_sec or 0), 3)
            break

        def rel(ts_ns) -> float:
            return round((int(ts_ns) - t0_ns) / 1e6, 3)

        app_slices: list[AppSlice] = []
        for row in tp.query(_SQL_APP_SLICES):
            app_slices.append(AppSlice(
                rel_ms=rel(row.ts),
                dur_ms=round(float(row.dur) / 1e6, 3),
                name=row.name,
                thread=row.thread_name,
            ))

        frames: list[FrameRow] = []
        for row in tp.query(_SQL_FRAMES):
            frames.append(FrameRow(
                rel_ms=rel(row.ts),
                dur_ms=round(float(row.dur_ms), 3),
                jank_type=row.jank_type if row.jank_type != "None" else None,
                on_time=int(row.on_time_finish or 0),
            ))

        recompose: list[RecomposeRow] = []
        for row in tp.query(_SQL_RECOMPOSE):
            recompose.append(RecomposeRow(
                rel_ms=rel(row.ts),
                dur_ms=round(float(row.dur) / 1e6, 3),
                name=row.name,
            ))

        main_top: list[MainTopEntry] = []
        for row in tp.query(_SQL_MAIN_TOP):
            main_top.append(MainTopEntry(
                name=row.norm_name,
                count=int(row.cnt),
                avg_ms=float(row.avg_ms or 0),
                max_ms=float(row.max_ms or 0),
                total_ms=float(row.total_ms or 0),
            ))

        all_names: list[SliceNameEntry] = []
        for row in tp.query(_SQL_ALL_NAMES):
            all_names.append(SliceNameEntry(
                name=row.name,
                thread=row.thread_name,
                count=int(row.cnt),
            ))

    finally:
        tp.close()

    return TraceSession(
        name=path.stem,
        duration_sec=dur_sec,
        app_slices=app_slices,
        frames=frames,
        recompose=recompose,
        main_top=main_top,
        all_names=all_names,
    )

# ── Хелперы ───────────────────────────────────────────────────────────────────

def _stats(vals: list) -> dict:
    if not vals:
        return {}
    return {
        "n":   len(vals),
        "min": round(min(vals), 2),
        "max": round(max(vals), 2),
        "avg": round(sum(vals) / len(vals), 2),
        "sum": round(sum(vals), 1),
    }

def _slice_stats(session: TraceSession, name: str) -> dict:
    return _stats([s.dur_ms for s in session.app_slices if s.name == name])

def _slice_count(session: TraceSession, name: str) -> int:
    return sum(1 for s in session.app_slices if s.name == name)

def _frame_avg(session: TraceSession) -> Optional[float]:
    durs = [f.dur_ms for f in session.frames]
    return round(sum(durs) / len(durs), 2) if durs else None

def _janky(session: TraceSession) -> list:
    """Кадры, которые до пользователя вовремя не дошли.

    Это либо App Deadline Missed, либо on_time=0. Buffer Stuffing сюда не попадает.
    """
    return [f for f in session.frames if f.jank_type == "App Deadline Missed" or f.on_time == 0]

def _jank_by_type(session: TraceSession) -> Counter:
    return Counter(f.jank_type for f in session.frames if f.jank_type)

def _recompose_rate(session: TraceSession) -> Optional[float]:
    if not session.recompose or not session.duration_sec:
        return None
    return round(len(session.recompose) / session.duration_sec, 1)

# ── Запись файлов ─────────────────────────────────────────────────────────────

def write_frames_csv(session: TraceSession, out_dir: Path):
    with open(out_dir / "frames.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_ms", "dur_ms", "jank_type", "on_time"])
        for fr in session.frames:
            w.writerow([fr.rel_ms, fr.dur_ms, fr.jank_type or "", fr.on_time])


def write_slices_csv(session: TraceSession, out_dir: Path):
    with open(out_dir / "slices.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_ms", "dur_ms", "name", "thread"])
        for s in session.app_slices:
            w.writerow([s.rel_ms, s.dur_ms, s.name, s.thread])


def write_recompose_csv(session: TraceSession, out_dir: Path):
    with open(out_dir / "recompose.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_ms", "dur_ms", "name"])
        for r in session.recompose:
            w.writerow([r.rel_ms, r.dur_ms, r.name])


def write_slice_names(session: TraceSession, out_dir: Path):
    with open(out_dir / "slice_names.txt", "w", encoding="utf-8") as f:
        f.write(f"# Все слайсы в процессе {APP_PACKAGE}, топ-50 по числу вызовов\n")
        f.write(f"# {'name':<55} {'thread':<25} {'count':>6}\n")
        f.write("# " + "─" * 88 + "\n")
        for e in session.all_names:
            f.write(f"  {e.name:<55} {e.thread:<25} {e.count:>6}\n")


def write_summary(session: TraceSession, out_dir: Path):
    fr_st     = _stats([f.dur_ms for f in session.frames])
    janky     = _janky(session)
    jank_cnt  = _jank_by_type(session)
    total_fr  = len(session.frames)
    janky_pct = round(len(janky) / total_fr * 100, 1) if total_fr else 0

    rc_durs  = [r.dur_ms for r in session.recompose]
    rc_st    = _stats(rc_durs)
    rc_names = Counter(r.name for r in session.recompose)
    rc_rate  = _recompose_rate(session)

    def _slice_line(name: str) -> str:
        st = _slice_stats(session, name)
        if not st:
            return f"  {name:<26} нет данных, нужен atrace_apps при снятии трейса"
        return (f"  {name:<26} count={st['n']:>5}  "
                f"avg={st['avg']:>8}ms  max={st['max']:>8}ms  total={st['sum']:>10}ms")

    lines = [
        "=== Perfetto Trace Analysis ===",
        f"Session:  {session.name}",
        f"Duration: {session.duration_sec:.1f} s",
        "",
        "Frames (SurfaceFlinger actual_frame_timeline):",
    ]
    if fr_st:
        smooth = total_fr - sum(jank_cnt.values())
        lines += [
            f"  total={total_fr}  avg={fr_st['avg']}ms  min={fr_st['min']}ms  max={fr_st['max']}ms",
            f"  smooth={smooth}  "
            + "  ".join(f"{t}={c}" for t, c in sorted(jank_cnt.items())),
            f"  late (on_time=0): {len(janky)} ({janky_pct}%)",
        ]
        if janky:
            worst = sorted(janky, key=lambda f: f.dur_ms, reverse=True)[:5]
            lines.append("  worst late frames (ms): " + ", ".join(f"{f.dur_ms}" for f in worst))
    else:
        lines.append(
            "  нет данных, нужен источник android.surfaceflinger.frametimeline"
        )

    lines += [
        "",
        "Custom slices (Trace.beginSection):",
        _slice_line("updateTiles"),
        _slice_line("flushPendingTiles"),
        _slice_line("decodeTile.wait"),
        _slice_line("decodeTile.decode"),
        "",
        "Recompositions (Compose):",
    ]
    if session.recompose:
        lines += [
            f"  count={len(session.recompose)}  rate={rc_rate}/sec  "
            f"avg={rc_st['avg']}ms  max={rc_st['max']}ms  total={rc_st['sum']}ms",
        ]
        for name, cnt in rc_names.most_common(5):
            lines.append(f"    {name}: {cnt}")
    else:
        lines.append(
            "  нет данных, нужны категории atrace app или view, "
            "либо Compose не оставил секций рекомпозиции в release"
        )

    lines += [
        "",
        f"Main thread top (depth=0, по суммарному времени):",
        f"  {'Name':<42} {'count':>6}  {'avg_ms':>8}  {'max_ms':>8}  {'total_ms':>10}",
        "  " + "─" * 80,
    ]
    if session.main_top:
        for e in session.main_top:
            lines.append(
                f"  {e.name:<42} {e.count:>6}  {e.avg_ms:>8}  {e.max_ms:>8}  {e.total_ms:>10}"
            )
    else:
        lines.append("  нет данных")

    lines += [
        "",
        "Diagnostics:",
        f"  Итого слайсов в процессе (уникальных имён): "
        f"{len({e.name for e in session.all_names})}",
        "  Файл slice_names.txt содержит полный список для проверки SQL-запросов.",
    ]

    with open(out_dir / "summary.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def write_outputs(session: TraceSession, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    write_frames_csv(session, out_dir)
    write_slices_csv(session, out_dir)
    write_recompose_csv(session, out_dir)
    write_slice_names(session, out_dir)
    write_summary(session, out_dir)

# ── Сравнение ─────────────────────────────────────────────────────────────────

def _delta(v1, v2, lower_is_better: bool = True, unit: str = "") -> str:
    """Разница между значениями для колонки сравнения.

    Значок ставится только у заметной разницы, чтобы мелкий разброс замера
    не выглядел результатом.
    """
    if v1 is None or v2 is None:
        return "n/a"
    delta = v2 - v1
    if delta == 0:
        return "="
    sign = "+" if delta > 0 else ""
    pct = abs(delta / v1 * 100) if v1 else 0
    if pct > 3 or abs(delta) >= 0.5:
        ok = (delta < 0) == lower_is_better
        icon = "✅" if ok else "❌"
    else:
        icon = ""
    return f"{sign}{round(delta, 2)}{unit} ({sign}{round(pct, 1)}%) {icon}".strip()


def write_comparison(s1: TraceSession, s2: TraceSession, path: Path):
    def row(label, v1, v2, lower=True, unit=""):
        v1s = f"{v1}{unit}" if v1 is not None else "n/a"
        v2s = f"{v2}{unit}" if v2 is not None else "n/a"
        d = _delta(v1, v2, lower, unit)
        return f"  {label:<38} {v1s:<16} {v2s:<16} {d}"

    def sa(s, name): return _slice_stats(s, name).get("avg")
    def sm(s, name): return _slice_stats(s, name).get("max")
    def sc(s, name): return _slice_count(s, name)

    lines = [
        "=== COMPARISON ===",
        f"  OLD: {s1.name}  ({s1.duration_sec:.1f}s)",
        f"  NEW: {s2.name}  ({s2.duration_sec:.1f}s)",
        "",
        f"  {'Metric':<38} {'OLD':<16} {'NEW':<16} DELTA",
        "  " + "─" * 86,
        "",
        "  --- Frames ---",
        row("Frames total",              len(s1.frames),       len(s2.frames),       lower=False),
        row("Frame avg ms",              _frame_avg(s1),       _frame_avg(s2),       lower=True, unit="ms"),
        row("App Deadline Missed",
            _jank_by_type(s1).get("App Deadline Missed", 0),
            _jank_by_type(s2).get("App Deadline Missed", 0), lower=True),
        row("Buffer Stuffing",
            _jank_by_type(s1).get("Buffer Stuffing", 0),
            _jank_by_type(s2).get("Buffer Stuffing", 0), lower=True),
        row("Late frames (on_time=0)",  len(_janky(s1)),      len(_janky(s2)),      lower=True),
        "",
        "  --- Custom slices ---",
        row("updateTiles count",       sc(s1,"updateTiles"),      sc(s2,"updateTiles"),      lower=True),
        row("updateTiles avg ms",      sa(s1,"updateTiles"),      sa(s2,"updateTiles"),      lower=True, unit="ms"),
        row("flushPendingTiles count", sc(s1,"flushPendingTiles"),sc(s2,"flushPendingTiles"),lower=True),
        row("decodeTile.wait count",   sc(s1,"decodeTile.wait"),  sc(s2,"decodeTile.wait"),  lower=True),
        row("decodeTile.wait avg ms",  sa(s1,"decodeTile.wait"),  sa(s2,"decodeTile.wait"),  lower=True, unit="ms"),
        row("decodeTile.wait max ms",  sm(s1,"decodeTile.wait"),  sm(s2,"decodeTile.wait"),  lower=True, unit="ms"),
        row("decodeTile.decode avg ms",sa(s1,"decodeTile.decode"),sa(s2,"decodeTile.decode"),lower=True, unit="ms"),
        row("decodeTile.decode max ms",sm(s1,"decodeTile.decode"),sm(s2,"decodeTile.decode"),lower=True, unit="ms"),
        "",
        "  --- Recompositions ---",
        row("Recompose count",         len(s1.recompose),    len(s2.recompose),    lower=True),
        row("Recompose rate /sec",     _recompose_rate(s1),  _recompose_rate(s2),  lower=True),
        row("Recompose avg ms",
            _stats([r.dur_ms for r in s1.recompose]).get("avg"),
            _stats([r.dur_ms for r in s2.recompose]).get("avg"),
            lower=True, unit="ms"),
    ]

    for label, sess in (("  OLD", s1), ("  NEW", s2)):
        names = Counter(r.name for r in sess.recompose)
        if names:
            lines.append(f"\n  Recompose names ({label.strip()}):")
            for name, cnt in names.most_common(5):
                lines.append(f"    {name}: {cnt}")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Анализирует Perfetto .pb трейсы PanZoomDemo."
    )
    parser.add_argument("trace", nargs="+", metavar="FILE.pb",
                        help="Один или два .pb файла, два дают comparison.txt")
    parser.add_argument("-o", "--out", default=None,
                        help="Папка вывода, по умолчанию logs/perfetto_analyzed/")
    args = parser.parse_args()

    if len(args.trace) > 2:
        print("Ошибка: максимум два файла для сравнения.", file=sys.stderr)
        sys.exit(1)

    project_root = Path(__file__).parent.parent
    base_out = Path(args.out) if args.out else project_root / "logs" / "perfetto_analyzed"

    sessions = []
    for trace_str in args.trace:
        p = Path(trace_str)
        if not p.exists():
            print(f"Файл не найден: {p}", file=sys.stderr)
            sys.exit(1)

        print(f"Анализирую {p.name} ...", end=" ", flush=True)
        session = analyze(p)
        out_dir = base_out / p.stem
        write_outputs(session, out_dir)
        fr_info = f"{len(session.frames)} frames" if session.frames else "no frame data"
        rc_info = f"{len(session.recompose)} recompose" if session.recompose else "no recompose data"
        sl_info = f"{len(session.app_slices)} app slices" if session.app_slices else "no atrace slices"
        print(f"-> {out_dir}/  [{fr_info}, {rc_info}, {sl_info}]")
        sessions.append(session)

    if len(sessions) == 2:
        cmp_path = base_out / "comparison.txt"
        write_comparison(sessions[0], sessions[1], cmp_path)
        print(f"Comparison -> {cmp_path}")


if __name__ == "__main__":
    main()
