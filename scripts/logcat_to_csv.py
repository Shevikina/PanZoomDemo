#!/usr/bin/env python3
"""
Конвертер Android Studio Logcat JSON → структурированные CSV/TSV для анализа PanZoomDemo.

Использование:
    python scripts/logcat_to_csv.py logs/<file>.logcat
    python scripts/logcat_to_csv.py logs/<old>.logcat logs/<new>.logcat
    python scripts/logcat_to_csv.py logs/<file>.logcat -o /tmp/out
"""

import argparse
import csv
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

APP_ID = "com.shevikina.panzoomdemo"
APP_TAG = "PanZoomDemo"

# ── Регексы ──────────────────────────────────────────────────────────────────

RE_FPS         = re.compile(r"FPS:\s*(\d+)")
RE_RAM_PSS     = re.compile(r"RAM PSS:\s*(\d+)MB")
RE_RAM_J       = re.compile(r"RAM J:\s*(\d+)MB")
RE_RAM_N       = re.compile(r"RAM N:\s*(\d+)MB")
RE_JANK_MS     = re.compile(r"Jank:\s*(\d+)ms")
RE_JANK_FPS    = re.compile(r"Jank frames/s:\s*(\d+)")
RE_POOL_STATS  = re.compile(
    r"pool stats: hit-rate=(\d+)%\s+items=(\d+)/(\d+)\s+buckets=(\d+)"
    r"\s+missMissing=(\d+)\s+missEmpty=(\d+)\s+evict=(\d+)\s+sizes=\[(.*?)\]"
)
RE_TILE_LOAD   = re.compile(
    r"decodeTile: wait=(\d+)ms decode=(\d+)ms \[(pool-\w+)\]"
    r" sampleSize=(\d+) \S+ (\d+)\S(\d+)px \[([^\]]+)\]"
)
RE_POOL_HIT    = re.compile(r"\[pool-hit\]")
RE_POOL_MISS   = re.compile(r"\[pool-miss\]")

# ── Dataclasses ───────────────────────────────────────────────────────────────

@dataclass
class LogMsg:
    rel_sec: float
    level: str
    tag: str
    app_id: str
    message: str

@dataclass
class MetricRow:
    rel_sec: float
    fps: Optional[int] = None
    ram_pss_mb: Optional[int] = None
    ram_j_mb: Optional[int] = None
    ram_n_mb: Optional[int] = None

@dataclass
class PoolSnapshot:
    rel_sec: float
    hit_rate_pct: int
    items: int
    max_items: int
    buckets: int
    miss_missing: int
    miss_empty: int
    evict: int
    sizes: str

@dataclass
class TileLoad:
    rel_sec: float
    wait_ms: int
    decode_ms: int
    pool_result: str
    sample_size: int
    tile_w: int
    tile_h: int
    worker: str

@dataclass
class JankRow:
    rel_sec: float
    jank_ms: Optional[int] = None
    jank_fps: Optional[int] = None

@dataclass
class EventRow:
    rel_sec: float
    level: str
    tag: str
    message: str

@dataclass
class Session:
    name: str
    total_messages: int
    app_messages: int
    duration_sec: float
    metrics: list = field(default_factory=list)
    pool_stats: list = field(default_factory=list)
    tile_loads: list = field(default_factory=list)
    jank: list = field(default_factory=list)
    events: list = field(default_factory=list)
    raw_pool_hits: int = 0
    raw_pool_misses: int = 0
    brd_warnings: int = 0
    exceptions: int = 0

# ── Парсинг ───────────────────────────────────────────────────────────────────

# threadtime: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
RE_TEXT_LINE = re.compile(
    r'^\d{2}-\d{2}\s+'
    r'(\d{2}:\d{2}:\d{2}\.\d+)'   # группа 1: время HH:MM:SS.mmm
    r'\s+(\d+)'                    # группа 2: PID
    r'\s+\d+'                      # TID (не нужен)
    r'\s+([VDIWEF])'               # группа 3: уровень
    r'\s+([^:]+?)'                 # группа 4: тег (без двоеточия)
    r':\s*(.*)'                    # группа 5: сообщение
)

def _parse_logcat_json(path: Path) -> tuple[list[LogMsg], int]:
    """Парсит JSON-экспорт Android Studio."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    all_msgs = data.get("logcatMessages", [])
    total = len(all_msgs)
    app_msgs = []
    for m in all_msgs:
        h = m.get("header", {})
        if h.get("applicationId") != APP_ID:
            continue
        ts = h.get("timestamp", {})
        t = ts.get("seconds", 0) + ts.get("nanos", 0) / 1e9
        app_msgs.append(LogMsg(
            rel_sec=t,
            level=h.get("logLevel", ""),
            tag=h.get("tag", ""),
            app_id=h.get("applicationId", ""),
            message=m.get("message", ""),
        ))
    return app_msgs, total

def _parse_logcat_text(path: Path) -> tuple[list[LogMsg], int]:
    """Парсит raw-текстовый лог (adb logcat -v threadtime).

    Определяет PID приложения по первому сообщению с тегом PanZoomDemo,
    затем включает все сообщения этого PID.
    """
    rows: list[tuple[int, str, str, float, str]] = []  # pid, level, tag, t, message
    app_pid: int | None = None

    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = RE_TEXT_LINE.match(line)
            if not m:
                continue
            time_str, pid_str, level, tag, message = m.groups()
            tag = tag.strip()
            pid = int(pid_str)

            # Время → секунды внутри суток (extract() всё равно вычтет t0)
            h, rest = time_str.split(":", 1)
            mi, sec = rest.split(":", 1)
            t = int(h) * 3600 + int(mi) * 60 + float(sec)

            rows.append((pid, level, tag, t, message))

            if app_pid is None and tag == APP_TAG:
                app_pid = pid

    total = len(rows)
    if app_pid is None:
        return [], total

    app_msgs = [
        LogMsg(rel_sec=t, level=level, tag=tag, app_id=APP_ID, message=message)
        for pid, level, tag, t, message in rows
        if pid == app_pid
    ]
    return app_msgs, total

def parse_logcat(path: Path) -> tuple[list[LogMsg], int]:
    """Определяет формат файла (JSON или raw text) и парсит."""
    with open(path, encoding="utf-8", errors="replace") as f:
        first_char = f.read(1)
    if first_char == "{":
        return _parse_logcat_json(path)
    return _parse_logcat_text(path)

# ── Извлечение метрик ─────────────────────────────────────────────────────────

def extract(msgs: list[LogMsg], name: str, total: int) -> Session:
    if not msgs:
        return Session(name=name, total_messages=total, app_messages=0, duration_sec=0)

    t0 = next((m.rel_sec for m in msgs if m.tag == APP_TAG), msgs[0].rel_sec)
    for m in msgs:
        m.rel_sec = round(m.rel_sec - t0, 3)

    session = Session(
        name=name,
        total_messages=total,
        app_messages=len(msgs),
        duration_sec=msgs[-1].rel_sec if msgs else 0,
    )

    # Группировка метрик по «тику»: сообщения в пределах 200 мс объединяются в одну строку.
    metric_buckets: dict[int, MetricRow] = {}

    def metric_key(rel_sec: float) -> int:
        return int(rel_sec * 5)  # шаг 200 мс

    brd_tag = "BitmapRegionDecoder"
    exc_keywords = ("Exception", "FATAL", "crash", "killed", "died", "ANR")

    pz_msgs = [m for m in msgs if m.tag == APP_TAG]

    for m in pz_msgs:
        msg = m.message
        t = m.rel_sec

        # ── FPS / RAM ────────────────────────────────────────────────────────
        fps_m = RE_FPS.search(msg)
        pss_m = RE_RAM_PSS.search(msg)
        j_m   = RE_RAM_J.search(msg)
        n_m   = RE_RAM_N.search(msg)

        if any((fps_m, pss_m, j_m, n_m)):
            key = metric_key(t)
            if key not in metric_buckets:
                metric_buckets[key] = MetricRow(rel_sec=t)
            row = metric_buckets[key]
            if fps_m: row.fps = int(fps_m.group(1))
            if pss_m: row.ram_pss_mb = int(pss_m.group(1))
            if j_m:   row.ram_j_mb  = int(j_m.group(1))
            if n_m:   row.ram_n_mb  = int(n_m.group(1))
            continue

        # ── Jank ─────────────────────────────────────────────────────────────
        jank_ms_m  = RE_JANK_MS.search(msg)
        jank_fps_m = RE_JANK_FPS.search(msg)
        if jank_ms_m or jank_fps_m:
            session.jank.append(JankRow(
                rel_sec=t,
                jank_ms =int(jank_ms_m.group(1))  if jank_ms_m  else None,
                jank_fps=int(jank_fps_m.group(1)) if jank_fps_m else None,
            ))
            continue

        # ── Pool stats ───────────────────────────────────────────────────────
        ps_m = RE_POOL_STATS.search(msg)
        if ps_m:
            session.pool_stats.append(PoolSnapshot(
                rel_sec     =t,
                hit_rate_pct=int(ps_m.group(1)),
                items       =int(ps_m.group(2)),
                max_items   =int(ps_m.group(3)),
                buckets     =int(ps_m.group(4)),
                miss_missing=int(ps_m.group(5)),
                miss_empty  =int(ps_m.group(6)),
                evict       =int(ps_m.group(7)),
                sizes       =ps_m.group(8).strip(),
            ))
            continue

        # ── Tile loads ───────────────────────────────────────────────────────
        tl_m = RE_TILE_LOAD.search(msg)
        if tl_m:
            result = tl_m.group(3).replace("pool-", "")
            session.tile_loads.append(TileLoad(
                rel_sec    =t,
                wait_ms    =int(tl_m.group(1)),
                decode_ms  =int(tl_m.group(2)),
                pool_result=result,
                sample_size=int(tl_m.group(4)),
                tile_w     =int(tl_m.group(5)),
                tile_h     =int(tl_m.group(6)),
                worker     =tl_m.group(7),
            ))
            if result == "hit":
                session.raw_pool_hits += 1
            else:
                session.raw_pool_misses += 1
            continue

        # ── Events (всё остальное) ───────────────────────────────────────────
        session.events.append(EventRow(rel_sec=t, level=m.level, tag=m.tag, message=msg))

    session.metrics = sorted(metric_buckets.values(), key=lambda r: r.rel_sec)

    # BitmapRegionDecoder и exceptions считаем по всем app-сообщениям
    for m in msgs:
        if m.tag == brd_tag:
            session.brd_warnings += 1
        if any(kw in m.message for kw in exc_keywords) and m.level not in ("DEBUG", "INFO"):
            session.exceptions += 1

    return session

# ── Запись файлов ─────────────────────────────────────────────────────────────

def _fmt(val) -> str:
    return "" if val is None else str(val)

def write_metrics(session: Session, out_dir: Path):
    path = out_dir / "metrics.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_sec", "fps", "ram_pss_mb", "ram_j_mb", "ram_n_mb"])
        for r in session.metrics:
            if any(v is not None for v in (r.fps, r.ram_pss_mb, r.ram_j_mb, r.ram_n_mb)):
                w.writerow([r.rel_sec, _fmt(r.fps), _fmt(r.ram_pss_mb), _fmt(r.ram_j_mb), _fmt(r.ram_n_mb)])

def write_pool_stats(session: Session, out_dir: Path):
    path = out_dir / "pool_stats.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_sec", "hit_rate_pct", "items", "max_items", "buckets",
                    "miss_missing", "miss_empty", "evict", "sizes"])
        for s in session.pool_stats:
            w.writerow([s.rel_sec, s.hit_rate_pct, s.items, s.max_items,
                        s.buckets, s.miss_missing, s.miss_empty, s.evict, s.sizes])

def write_tile_loads(session: Session, out_dir: Path):
    path = out_dir / "tile_loads.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_sec", "wait_ms", "decode_ms", "pool_result",
                    "sample_size", "tile_w", "tile_h", "worker"])
        for t in session.tile_loads:
            w.writerow([t.rel_sec, t.wait_ms, t.decode_ms, t.pool_result,
                        t.sample_size, t.tile_w, t.tile_h, t.worker])

def write_jank(session: Session, out_dir: Path):
    path = out_dir / "jank.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rel_sec", "jank_ms", "jank_fps"])
        for j in session.jank:
            w.writerow([j.rel_sec, _fmt(j.jank_ms), _fmt(j.jank_fps)])

def write_events(session: Session, out_dir: Path):
    path = out_dir / "events.tsv"
    with open(path, "w", encoding="utf-8") as f:
        f.write("rel_sec\tlevel\ttag\tmessage\n")
        for e in session.events:
            msg = e.message.replace("\t", " ").replace("\n", " ")
            f.write(f"{e.rel_sec}\t{e.level}\t{e.tag}\t{msg}\n")

def _stats(vals: list) -> dict:
    if not vals:
        return {"n": 0}
    return {"n": len(vals), "min": min(vals), "max": max(vals),
            "avg": round(sum(vals) / len(vals), 1)}

def _dist(vals: list) -> str:
    from collections import Counter
    c = Counter(vals)
    return "{" + ", ".join(f"{k}:{v}" for k, v in sorted(c.items())) + "}"

def write_summary(session: Session, out_dir: Path):
    path = out_dir / "summary.txt"

    fps_vals    = [r.fps         for r in session.metrics if r.fps         is not None]
    pss_vals    = [r.ram_pss_mb  for r in session.metrics if r.ram_pss_mb  is not None]
    j_vals      = [r.ram_j_mb   for r in session.metrics if r.ram_j_mb   is not None]
    n_vals      = [r.ram_n_mb   for r in session.metrics if r.ram_n_mb   is not None]
    jank_ms_vals= [j.jank_ms    for j in session.jank    if j.jank_ms    is not None]
    dec_vals    = [t.decode_ms  for t in session.tile_loads]
    wait_vals   = [t.wait_ms    for t in session.tile_loads]

    from collections import Counter
    ss_dist    = _dist([t.sample_size for t in session.tile_loads])
    size_dist  = dict(Counter(f"{t.tile_w}x{t.tile_h}" for t in session.tile_loads).most_common(8))

    hit_total  = session.raw_pool_hits + session.raw_pool_misses
    hit_rate   = f"{session.raw_pool_hits / hit_total * 100:.1f}%" if hit_total else "n/a"

    last_pool  = session.pool_stats[-1] if session.pool_stats else None

    toggles    = [e for e in session.events if "Toggle" in e.message]

    def fmt_stats(d: dict, unit: str = "") -> str:
        if not d.get("n"):
            return "нет данных"
        return (f"samples={d['n']}  min={d['min']}{unit}  "
                f"max={d['max']}{unit}  avg={d['avg']}{unit}")

    lines = [
        f"Session:  {session.name}",
        f"Duration: {session.duration_sec:.1f} s   "
        f"App messages: {session.app_messages}  (total in log: {session.total_messages})",
        "",
        "FPS:",
        f"  {fmt_stats(_stats(fps_vals))}",
        f"  distribution: {_dist(fps_vals)}" if fps_vals else "  нет данных",
        "",
        "Jank:",
        f"  events={len(jank_ms_vals)}  " +
        (f"min={min(jank_ms_vals)}ms  max={max(jank_ms_vals)}ms  "
         f"avg={round(sum(jank_ms_vals)/len(jank_ms_vals),1)}ms" if jank_ms_vals else "нет данных"),
        f"  all values (ms): {sorted(jank_ms_vals)}" if jank_ms_vals else "",
        "",
        f"RAM PSS: {fmt_stats(_stats(pss_vals), 'MB')}",
        f"RAM J:   {fmt_stats(_stats(j_vals), 'MB')}",
        f"RAM N:   {fmt_stats(_stats(n_vals), 'MB')}",
        "",
        f"Pool hit-rate (raw tile ops): "
        f"{session.raw_pool_hits} hits / {hit_total} total = {hit_rate}",
        f"Pool stats snapshots: {len(session.pool_stats)}",
    ]
    if last_pool:
        lines.append(
            f"  Final: hit-rate={last_pool.hit_rate_pct}%  "
            f"items={last_pool.items}/{last_pool.max_items}  "
            f"miss_missing={last_pool.miss_missing}  "
            f"miss_empty={last_pool.miss_empty}  "
            f"evict={last_pool.evict}"
        )
    if session.pool_stats:
        cold = session.pool_stats[1] if len(session.pool_stats) > 1 else session.pool_stats[0]
        lines.append(
            f"  Cold-start (2nd snapshot): miss_missing={cold.miss_missing}  evict={cold.evict}"
        )
    lines += [
        "",
        f"Decode: {fmt_stats(_stats(dec_vals), 'ms')}",
        f"Wait:   {fmt_stats(_stats(wait_vals), 'ms')}",
        f"Sample sizes used: {ss_dist}",
        f"Tile sizes used:   {size_dist}",
        "",
        f"Toggles ({len(toggles)}):",
    ]
    for e in toggles:
        lines.append(f"  +{e.rel_sec:.2f}s  {e.message}")
    lines += [
        "",
        f"BitmapRegionDecoder warnings: {session.brd_warnings}",
        f"Exceptions/crashes:           {session.exceptions}",
    ]

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

def write_outputs(session: Session, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    write_metrics(session, out_dir)
    write_pool_stats(session, out_dir)
    write_tile_loads(session, out_dir)
    write_jank(session, out_dir)
    write_events(session, out_dir)
    write_summary(session, out_dir)

# ── Сравнение ─────────────────────────────────────────────────────────────────

def _delta_str(old_val, new_val, lower_is_better: bool = True) -> str:
    if old_val is None or new_val is None:
        return "n/a"
    delta = new_val - old_val
    if delta == 0:
        return "0"
    sign = "+" if delta > 0 else ""
    pct = abs(delta / old_val * 100) if old_val else 0
    # Значок: зелёный если улучшение, красный если регрессия
    if pct > 5 or abs(delta) >= 10:
        is_improvement = (delta < 0) == lower_is_better
        icon = "✅" if is_improvement else "❌"
    else:
        icon = ""
    return f"{sign}{round(delta, 1)} {icon}".strip()

def write_comparison(s1: Session, s2: Session, path: Path):
    fps1   = _stats([r.fps        for r in s1.metrics if r.fps        is not None])
    fps2   = _stats([r.fps        for r in s2.metrics if r.fps        is not None])
    pss1   = _stats([r.ram_pss_mb for r in s1.metrics if r.ram_pss_mb is not None])
    pss2   = _stats([r.ram_pss_mb for r in s2.metrics if r.ram_pss_mb is not None])
    n1     = _stats([r.ram_n_mb   for r in s1.metrics if r.ram_n_mb   is not None])
    n2     = _stats([r.ram_n_mb   for r in s2.metrics if r.ram_n_mb   is not None])
    j1     = _stats([r.ram_j_mb   for r in s1.metrics if r.ram_j_mb   is not None])
    j2     = _stats([r.ram_j_mb   for r in s2.metrics if r.ram_j_mb   is not None])
    jk1    = [j.jank_ms for j in s1.jank if j.jank_ms is not None]
    jk2    = [j.jank_ms for j in s2.jank if j.jank_ms is not None]
    jks1   = _stats(jk1)
    jks2   = _stats(jk2)
    dec1   = _stats([t.decode_ms for t in s1.tile_loads])
    dec2   = _stats([t.decode_ms for t in s2.tile_loads])
    wait1  = _stats([t.wait_ms   for t in s1.tile_loads])
    wait2  = _stats([t.wait_ms   for t in s2.tile_loads])

    h1t = s1.raw_pool_hits + s1.raw_pool_misses
    h2t = s2.raw_pool_hits + s2.raw_pool_misses
    hr1 = round(s1.raw_pool_hits / h1t * 100, 1) if h1t else None
    hr2 = round(s2.raw_pool_hits / h2t * 100, 1) if h2t else None

    cold1_mm = s1.pool_stats[1].miss_missing if len(s1.pool_stats) > 1 else None
    cold2_mm = s2.pool_stats[1].miss_missing if len(s2.pool_stats) > 1 else None
    cold1_ev = s1.pool_stats[1].evict        if len(s1.pool_stats) > 1 else None
    cold2_ev = s2.pool_stats[1].evict        if len(s2.pool_stats) > 1 else None

    def row(label, v1, v2, lower_is_better=True, unit=""):
        u = unit
        v1s = f"{v1}{u}" if v1 is not None else "n/a"
        v2s = f"{v2}{u}" if v2 is not None else "n/a"
        d   = _delta_str(v1, v2, lower_is_better)
        return f"  {label:<30} {v1s:<12} {v2s:<12} {d}"

    def g(d: dict, key: str):
        return d.get(key)

    lines = [
        f"=== COMPARISON ===",
        f"  OLD: {s1.name}",
        f"  NEW: {s2.name}",
        "",
        f"  {'Metric':<30} {'OLD':<12} {'NEW':<12} {'DELTA'}",
        "  " + "─" * 62,
        row("FPS avg",             g(fps1,"avg"),  g(fps2,"avg"),  lower_is_better=False),
        row("FPS min",             g(fps1,"min"),  g(fps2,"min"),  lower_is_better=False),
        row("Jank events",         len(jk1),       len(jk2)),
        row("Jank max ms",         g(jks1,"max"),  g(jks2,"max")),
        row("Jank avg ms",         g(jks1,"avg"),  g(jks2,"avg")),
        row("Decode avg ms",       g(dec1,"avg"),  g(dec2,"avg")),
        row("Decode max ms",       g(dec1,"max"),  g(dec2,"max")),
        row("Wait avg ms",         g(wait1,"avg"), g(wait2,"avg")),
        row("Wait max ms",         g(wait1,"max"), g(wait2,"max")),
        row("RAM PSS avg MB",      g(pss1,"avg"),  g(pss2,"avg"),  lower_is_better=True),
        row("RAM PSS max MB",      g(pss1,"max"),  g(pss2,"max"),  lower_is_better=True),
        row("RAM Native avg MB",   g(n1,"avg"),    g(n2,"avg"),    lower_is_better=True),
        row("RAM Native max MB",   g(n1,"max"),    g(n2,"max"),    lower_is_better=True),
        row("RAM Java avg MB",     g(j1,"avg"),    g(j2,"avg"),    lower_is_better=True),
        row("Pool hit-rate %",     hr1,            hr2,            lower_is_better=False),
        row("Pool hits (raw)",     s1.raw_pool_hits,s2.raw_pool_hits, lower_is_better=False),
        row("Pool misses (raw)",   s1.raw_pool_misses, s2.raw_pool_misses),
        row("Cold-start missMissing", cold1_mm,   cold2_mm),
        row("Cold-start evictions",   cold1_ev,   cold2_ev),
        row("BitmapRegDecoder warns", s1.brd_warnings, s2.brd_warnings),
        row("Tile loads total",    len(s1.tile_loads), len(s2.tile_loads), lower_is_better=False),
        "",
        "  Pool stats (final snapshot):",
    ]
    for label, sess in (("  OLD", s1), ("  NEW", s2)):
        if sess.pool_stats:
            p = sess.pool_stats[-1]
            lines.append(
                f"  {label}: hit={p.hit_rate_pct}%  items={p.items}/{p.max_items}  "
                f"miss_missing={p.miss_missing}  miss_empty={p.miss_empty}  evict={p.evict}"
            )

    lines += ["", "  Sample sizes:"]
    from collections import Counter
    for label, sess in (("  OLD", s1), ("  NEW", s2)):
        ss = dict(sorted(Counter(t.sample_size for t in sess.tile_loads).items()))
        lines.append(f"  {label}: {ss}")

    lines += ["", "  Tile output sizes (top-5):"]
    for label, sess in (("  OLD", s1), ("  NEW", s2)):
        ts = Counter(f"{t.tile_w}x{t.tile_h}" for t in sess.tile_loads).most_common(5)
        lines.append(f"  {label}: {dict(ts)}")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Конвертирует Android Studio Logcat JSON в CSV для анализа PanZoomDemo."
    )
    parser.add_argument("logcat", nargs="+", metavar="FILE.logcat",
                        help="Один или два .logcat файла")
    parser.add_argument("-o", "--out", default=None,
                        help="Папка вывода (по умолчанию: logs/logcat_analyzed/)")
    args = parser.parse_args()

    if len(args.logcat) > 2:
        print("Ошибка: максимум два файла для сравнения.", file=sys.stderr)
        sys.exit(1)

    project_root = Path(__file__).parent.parent
    base_out     = Path(args.out) if args.out else project_root / "logs" / "logcat_analyzed"

    sessions = []
    for logcat_path in args.logcat:
        p = Path(logcat_path)
        if not p.exists():
            print(f"Файл не найден: {p}", file=sys.stderr)
            sys.exit(1)

        msgs, total = parse_logcat(p)
        session = extract(msgs, p.stem, total)

        out_dir = base_out / p.stem
        write_outputs(session, out_dir)

        print(f"Parsed {total} messages, {session.app_messages} app → {out_dir}/")
        sessions.append(session)

    if len(sessions) == 2:
        cmp_path = base_out / "comparison.txt"
        write_comparison(sessions[0], sessions[1], cmp_path)
        print(f"Comparison → {cmp_path}")

if __name__ == "__main__":
    main()
