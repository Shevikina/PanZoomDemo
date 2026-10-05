#!/usr/bin/env python3
"""
Проверяет, годится ли снятый прогон для анализа.

Конвертер считает метрики, но не спрашивает, ту ли конфигурацию сняли. Этот скрипт
читает сырой logcat и сверяет его с ожиданиями кейса. Проверяет ассет, режим, положение
graphicsLayer и то, не уехала ли конфигурация в середине записи.

    python3 scripts/baseline_verify.py <прогон>.logcat --case tiled-clg-large \
        --asset LARGE --mode Tiled --graphics-layer on --kind steady

Флаг --describe переключает режим. Вместо сверки с ожиданиями скрипт восстанавливает
конфигурацию из самого лога и печатает таблицу по нескольким файлам сразу.

Возвращает 0, когда прогон пригоден, пусть и с замечаниями, и 1, когда непригоден.
"""

import argparse
import pathlib
import re
import sys
from datetime import datetime, timedelta

RE_TS = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})")
RE_APP = re.compile(r"\bPanZoomDemo\b")
RE_ASSET = re.compile(r"Image test_image_(\w+)\.jpg is open")
RE_TILED = re.compile(r"recompose\[TiledImageViewer\]|recompose\[Content\]|updateTiles:")
# Признаки тайлового режима из аналитических строк. Аналитика вместе с горячими логами
# не гаснет, а декод тайлов и пул буферов существуют только в тайловом режиме, поэтому
# при выключенных горячих логах режим определяется только по ним.
RE_TILED_ANALYTICS = re.compile(r"decodeTile:|pool stats:")
RE_GL = re.compile(r"graphicsLayer=(true|false)")
RE_FPS = re.compile(r"FPS:\s*(\d+)")
# Число пробуждений кадрового цикла за секунду. Это не FPS, потому что цикл спит, пока
# нет работы, и на гладком прогоне просыпается реже, чем идут кадры. Держим только для
# справки, а настоящие кадры берём из строк FPS и из gfxinfo.
RE_WAKEUPS = re.compile(r"frameLoop: wakeups=(\d+)/s")
RE_RAM = re.compile(r"RAM PSS:\s*(\d+)MB")
RE_SAMPLE = re.compile(r"sampleSize=(\d+)")
RE_NEEDED = re.compile(r"needed=(\d+)")
RE_FAILURE = re.compile(r"FATAL EXCEPTION|OutOfMemoryError|Наивная (загрузка|отрисовка)")
RE_SCALE = re.compile(r"updateTiles: scale=(\d+)%")

# Границы маршрута жеста для A/B-прогона, в процентах масштаба.
#
# Отдаление упирается в MIN_SCALE приложения, то есть в 10 процентов, и доходит туда
# стабильно, поэтому порог стоит вплотную к упору.
#
# Приближение упирается в MAX_SCALE, то есть в 1000 процентов, но за отведённые секунды
# до упора доходит не всегда. Поэтому порог стоит низко и ловит только те прогоны,
# где приближения почти не было.
ROUTE_ZOOM_OUT_MAX_PCT = 15
ROUTE_ZOOM_IN_MIN_PCT = 300

# Длительность в секундах, ниже которой прогон слишком короткий, чтобы метрики
# успели устояться.
MIN_STEADY_SECONDS = 20.0

# Какую долю отсчёта обязан покрыть лог. Порог нужен, потому что на загруженном
# устройстве logcat отстаёт, хвост записи в файл не доходит, и обрезанный лог выглядит
# так, будто жеста не было.
MIN_CAPTURE_SHARE = 0.9

APP_PACKAGE = "com.shevikina.panzoomdemo"
# Сколько строк после FATAL EXCEPTION смотреть в поисках имени процесса. В дампе краша
# оно идёт не в самой строке отказа, а в нескольких следующих.
CRASH_LOOKAHEAD_LINES = 5


def ts(line):
    """Метка времени строки threadtime. None, если строка её не начинает.

    Года в формате нет, поэтому берём любой, а разницу считаем в пределах суток.
    """
    m = RE_TS.match(line)
    if not m:
        return None
    mo, d, h, mi, sec, ms = (int(x) for x in m.groups())
    return datetime(2000, mo, d, h, mi, sec, ms * 1000)


class Report:
    """Копит результаты проверок. FAIL означает, что прогон надо переснять."""

    def __init__(self):
        self.rows = []
        self.failed = False

    def ok(self, what, detail=""):
        self.rows.append(("OK  ", what, detail))

    def warn(self, what, detail=""):
        self.rows.append(("WARN", what, detail))

    def fail(self, what, detail=""):
        self.rows.append(("FAIL", what, detail))
        self.failed = True

    def note(self, what, detail=""):
        self.rows.append(("--  ", what, detail))

    def render(self):
        width = max(len(w) for _, w, _ in self.rows)
        for status, what, detail in self.rows:
            line = f"  [{status}] {what.ljust(width)}"
            if detail:
                line += f"  {detail}"
            print(line)


RE_GFX_TOTAL = re.compile(r"Total frames rendered:\s*(\d+)")
RE_GFX_JANKY = re.compile(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)")
RE_GFX_PCT = re.compile(r"(\d+)th percentile:\s*(\d+)ms")


def read_gfxinfo(path):
    """Счётчики кадров из дампа `dumpsys gfxinfo`. None, если счётчиков в нём нет.

    По кадрам это единственный источник, потому что logcat их не считает.
    """
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return None
    total = RE_GFX_TOTAL.search(text)
    janky = RE_GFX_JANKY.search(text)
    if not total:
        return None
    return dict(
        total=int(total.group(1)),
        janky=int(janky.group(1)) if janky else 0,
        janky_pct=f"{janky.group(2)}%" if janky else "?",
        percentiles={f"{m.group(1)}th": f"{m.group(2)}ms" for m in RE_GFX_PCT.finditer(text)},
    )


def read(path):
    """Всё, что проверки берут из лога, одним проходом по файлу."""
    first = last = None
    app_lines = 0
    assets, gl_values, sample_sizes, needed = [], set(), set(), []
    # Пары из метки времени и масштаба в процентах. По ним видно, прошёл ли жест
    # оба края зума.
    scales = []
    fps, wakeups, ram, tiled_at, failure_at = [], [], [], None, None
    tiled_analytics_at = None

    # Краш процесса пишется тегом AndroidRuntime, а не тегом приложения, поэтому отказ
    # ищем до фильтра по тегу. Чтобы не поймать чужое падение, FATAL от постороннего
    # тега засчитываем только тогда, когда рядом всплыло имя пакета.
    pending_crash = None
    pending_left = 0

    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            t = ts(line)
            if t is not None:
                if first is None:
                    first = t
                last = t

            if failure_at is None:
                if pending_left > 0:
                    if APP_PACKAGE in line:
                        failure_at = pending_crash
                        pending_left = 0
                    else:
                        pending_left -= 1
                if failure_at is None and RE_FAILURE.search(line):
                    if RE_APP.search(line) or APP_PACKAGE in line:
                        failure_at = t
                    elif pending_left == 0:
                        pending_crash = t
                        pending_left = CRASH_LOOKAHEAD_LINES

            if not RE_APP.search(line):
                continue
            app_lines += 1

            m = RE_ASSET.search(line)
            if m:
                assets.append(m.group(1).upper())
            if RE_TILED.search(line) and tiled_at is None:
                tiled_at = t
            if RE_TILED_ANALYTICS.search(line) and tiled_analytics_at is None:
                tiled_analytics_at = t
            m = RE_GL.search(line)
            if m:
                gl_values.add(m.group(1))
            m = RE_FPS.search(line)
            if m:
                fps.append(int(m.group(1)))
            m = RE_WAKEUPS.search(line)
            if m:
                wakeups.append(int(m.group(1)))
            m = RE_RAM.search(line)
            if m:
                ram.append(int(m.group(1)))
            m = RE_SCALE.search(line)
            if m and t is not None:
                scales.append((t, int(m.group(1))))
            m = RE_SAMPLE.search(line)
            if m:
                sample_sizes.add(int(m.group(1)))
            m = RE_NEEDED.search(line)
            if m:
                needed.append(int(m.group(1)))

    duration = 0.0
    if first and last:
        delta = last - first
        if delta < timedelta(0):
            delta += timedelta(days=1)
        duration = delta.total_seconds()

    failure_offset = None
    if failure_at and first:
        delta = failure_at - first
        if delta < timedelta(0):
            delta += timedelta(days=1)
        failure_offset = delta.total_seconds()

    # Метки времени переводим в смещения от начала записи, чтобы отчёт говорил
    # «на 24-й секунде», а не абсолютным временем.
    route = []
    if first:
        for t, pct in scales:
            delta = t - first
            if delta < timedelta(0):
                delta += timedelta(days=1)
            route.append((delta.total_seconds(), pct))

    return dict(
        duration=duration, app_lines=app_lines, assets=assets, gl=gl_values,
        sample_sizes=sample_sizes, needed=needed, fps=fps, wakeups=wakeups, ram=ram,
        route=route,
        tiled=tiled_at is not None, tiled_analytics=tiled_analytics_at is not None,
        failure_offset=failure_offset,
    )


def check_route(r, d, a):
    """Сверяет маршрут жеста с фазами A/B, то есть отдаление и приближение до краёв.

    Масштаб виден только в горячей строке updateTiles. Без горячих логов и вне тайлового
    режима проверять нечего, и это не брак прогона.
    """
    # При выключенном culling набор нужных тайлов равен всей сетке и от зума не зависит.
    # Грузить и выгружать после первой загрузки нечего, поэтому строка updateTiles
    # не пишется и масштаба в логе нет. Жест при этом идёт как надо, проверить его нечем.
    if a.culling == "off":
        r.note("маршрут жеста", "не проверяется, при выключенном culling набор тайлов "
                                "постоянный и масштаб в лог не попадает")
        return

    route = d["route"]
    if not route:
        if a.hot_logs == "off":
            r.note("маршрут жеста", "строку updateTiles гасят выключенные горячие логи")
        elif a.mode != "Tiled":
            r.note("маршрут жеста", "не проверяется, масштаб пишет только тайловый режим")
        else:
            r.fail("маршрут жеста", "ни одной строки updateTiles, жеста в записи не было")
        return

    lo_at, lo = min(route, key=lambda x: x[1])
    hi_at, hi = max(route, key=lambda x: x[1])

    if lo <= ROUTE_ZOOM_OUT_MAX_PCT:
        r.ok("отдаление", f"до {lo}% на {lo_at:.0f}-й секунде")
    else:
        r.fail("отдаление", f"только до {lo}%, ждали {ROUTE_ZOOM_OUT_MAX_PCT}% и ниже")

    if hi >= ROUTE_ZOOM_IN_MIN_PCT:
        r.ok("приближение", f"до {hi}% на {hi_at:.0f}-й секунде")
    else:
        r.fail("приближение", f"только до {hi}%, ждали {ROUTE_ZOOM_IN_MIN_PCT}% и выше")

    # Порядок фаз это замечание, а не брак. Оба края пройдены, значит уровни LOD в логе
    # те же самые, и переснимать из-за одного порядка не стоит.
    if lo_at > hi_at:
        r.note("порядок зума", "приближение раньше отдаления, фазы шли не по плану")


def describe(paths):
    """Печатает таблицу с конфигурацией, восстановленной из самих логов.

    Нужна для логов, у которых ожиданий нет, поэтому вместо вердикта о конфигурации
    показывает, что в прогоне вообще было.

    Режим и graphicsLayer берутся из лога как есть. Culling и LOD выводятся косвенно,
    по разбросу sampleSize и needed, и потому помечены как догадка.
    """
    rows = []
    for path in paths:
        try:
            d = read(path)
        except OSError as e:
            rows.append((pathlib.Path(path).name, f"не прочитан: {e}", "", "", "", "", "", "", ""))
            continue

        name = pathlib.Path(path).name
        asset = " → ".join(dict.fromkeys(d["assets"])) or "?"
        if not d["app_lines"]:
            mode = "?"
        elif d["tiled"] or d["tiled_analytics"]:
            mode = "Tiled" if not d["failure_offset"] else "Tiled (после отказа)"
        else:
            mode = "Naive"
        gl = "/".join(sorted(d["gl"])) if d["gl"] else "?"
        # Если LOD выключен, то sampleSize всю запись один. Если выключен culling,
        # то needed постоянен.
        lod = "?" if not d["sample_sizes"] else ("on" if len(d["sample_sizes"]) > 1 else "off?")
        if not d["needed"]:
            cull = "?"
        else:
            cull = "on" if min(d["needed"]) != max(d["needed"]) else "off?"
        fail = f"{d['failure_offset']:.0f}s" if d["failure_offset"] is not None else "-"
        usable = "да" if d["app_lines"] and (d["fps"] or d["wakeups"]) else "нет"
        rows.append((
            name, f"{d['duration']:.0f}s", str(d["app_lines"]), asset, mode, gl, lod, cull, fail, usable,
        ))

    head = ("ФАЙЛ", "ДЛИТ", "СТРОК", "АССЕТ", "РЕЖИМ", "GL", "LOD", "CULL", "ОТКАЗ", "ГОДЕН")
    widths = [max(len(r[i]) for r in ([head] + rows)) for i in range(len(head))]
    print()
    print("  " + "  ".join(h.ljust(w) for h, w in zip(head, widths)))
    print("  " + "  ".join("-" * w for w in widths))
    for r in rows:
        print("  " + "  ".join(c.ljust(w) for c, w in zip(r, widths)))
    print()
    print("  LOD и CULL в лог не пишутся, это догадка по разбросу sampleSize и needed.")
    print("  ГОДЕН=нет означает, что в логе нет сообщений приложения или нет строк FPS.")
    print()
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logcat", nargs="+")
    ap.add_argument("--describe", action="store_true",
                    help="не сверять с ожиданиями, а восстановить конфигурацию из лога")
    ap.add_argument("--case")
    ap.add_argument("--asset", choices=["SMALL", "MEDIUM", "LARGE"])
    ap.add_argument("--mode", choices=["Naive", "Tiled"])
    ap.add_argument("--graphics-layer", default="n/a", choices=["on", "off", "n/a"])
    ap.add_argument("--kind", choices=["steady", "failure"])
    # Что запускает целевую конфигурацию внутри записи. При триггере по режиму ассет
    # грузится ещё на этапе настройки, поэтому его отсутствие в окне записи это норма.
    ap.add_argument("--trigger", default="asset", choices=["asset", "mode"])
    ap.add_argument("--gfxinfo", help="дамп dumpsys gfxinfo того же прогона")
    # Положение culling. В лог оно не пишется, но от него зависит, попадёт ли в лог
    # масштаб, поэтому проверка маршрута его спрашивает.
    ap.add_argument("--culling", default="n/a", choices=["on", "off", "n/a"])
    ap.add_argument("--expect-seconds", type=float,
                   help="сколько секунд длился отсчёт: столько же обязан покрыть лог")
    ap.add_argument("--route", action="store_true",
                   help="прогон шёл по фазам A/B: проверить, что жест прошёл оба края зума")
    # При выключенных горячих логах в логе нет ни recompose[...], ни updateTiles,
    # ни frameLoop, потому что их гасит BuildConfig.HOT_LOGS. Это штатный комплект
    # замеров, а не брак прогона, поэтому опирающиеся на эти строки проверки снимаются.
    ap.add_argument("--hot-logs", default="on", choices=["on", "off", "unknown"])
    # Чего ждать от прогона по части отказа. certain означает, что платформа откажет
    # гарантированно, потому что битмап заведомо не рисуется. predicted означает отказ,
    # посчитанный по коду, но ни разу не наблюдавшийся. no означает, что отказа быть
    # не должно.
    ap.add_argument("--failure", default="no", choices=["certain", "predicted", "no"])
    a = ap.parse_args()

    if a.describe:
        return describe(a.logcat)

    missing = [n for n in ("case", "asset", "mode", "kind") if getattr(a, n) is None]
    if missing:
        ap.error("без --describe обязательны: " + ", ".join("--" + n for n in missing))
    if len(a.logcat) != 1:
        ap.error("сверка делается по одному файлу; для нескольких используй --describe")

    d = read(a.logcat[0])
    r = Report()

    print(f"\nПроверка прогона {a.case}:")

    # 1. Есть ли вообще что анализировать.
    if d["app_lines"] == 0:
        r.fail("сообщения приложения", "их нет, значит не тот buildType или запись мимо окна")
    else:
        r.ok("сообщения приложения", f"{d['app_lines']} строк за {d['duration']:.0f}s")

    # 2. Длительность. Если прогон оборвал отказ, то короткая запись нормальна,
    # и неважно, ждали мы этот отказ или нет.
    if d["failure_offset"] is None and d["duration"] < MIN_STEADY_SECONDS:
        r.warn("длительность", f"{d['duration']:.0f}s < {MIN_STEADY_SECONDS:.0f}s, метрики не устоялись")
    else:
        r.ok("длительность", f"{d['duration']:.0f}s")

    # 2б. Полнота записи. Проверяется раньше маршрута, потому что обрезанный хвост роняет
    # именно маршрут, и без этой строки причина выглядит как незавершённый жест.
    if a.expect_seconds and d["failure_offset"] is None:
        want = a.expect_seconds * MIN_CAPTURE_SHARE
        if d["duration"] < want:
            r.fail("полнота записи",
                   f"в логе {d['duration']:.0f}s из {a.expect_seconds:.0f}s отсчёта, "
                   f"хвост не дописался и всё после этого в файл не попало")
        else:
            r.ok("полнота записи", f"{d['duration']:.0f}s из {a.expect_seconds:.0f}s отсчёта")

    # 3. Ассет. Загрузка каждого ассета оставляет "Image test_image_X.jpg is open".
    if not d["assets"]:
        if a.trigger == "mode":
            r.note("ассет", f"{a.asset} загружен на этапе настройки, в окне записи его нет, это норма")
        else:
            r.warn("ассет", "в логе нет ни одной загрузки - ассет переключили до записи?")
    elif d["assets"][-1] != a.asset:
        r.fail("ассет", f"ожидался {a.asset}, последним загружен {d['assets'][-1]}")
    elif len(set(d["assets"])) > 1:
        r.warn("ассет", f"{a.asset}, но за запись их было несколько: {' → '.join(d['assets'])}")
    else:
        r.ok("ассет", a.asset)

    # 4. Режим. С горячими логами тайловый вьюер шумит на каждой рекомпозиции, без них
    # остаются только decodeTile и pool stats. Их достаточно, потому что в наивном режиме
    # нет ни регионального декодера, ни пула буферов.
    tiled_seen = d["tiled"] or d["tiled_analytics"]
    how = "по рекомпозициям" if d["tiled"] else "по декоду тайлов"
    if a.mode == "Tiled":
        if tiled_seen:
            r.ok("режим", f"Tiled, тайловый вьюер в логе есть ({how})")
        else:
            r.fail("режим", "ожидался Tiled, но следов тайлового вьюера нет")
    elif a.kind == "steady":
        if tiled_seen:
            r.fail("режим", "ожидался Naive, но в логе работает тайловый вьюер, режим откатился")
        else:
            r.ok("режим", "Naive, тайлового вьюера в логе нет")
    else:
        r.note("режим", "Naive с ожидаемым откатом в Tiled, проверять нечего")

    # 5. graphicsLayer, единственный тумблер, который виден в логе буквально.
    # Его пишет строка recompose[Content] из TiledImageViewerContent.
    expected_gl = {"on": "true", "off": "false"}.get(a.graphics_layer)
    if expected_gl is None:
        r.note("graphicsLayer", "для этого кейса не проверяется")
    elif a.hot_logs == "off":
        r.note("graphicsLayer", "не проверяется, строку recompose гасят выключенные горячие логи")
    elif not d["gl"]:
        r.warn("graphicsLayer", "в логе нет ни одной отрисовки тайлов")
    elif d["gl"] == {expected_gl}:
        r.ok("graphicsLayer", expected_gl)
    elif expected_gl in d["gl"]:
        r.warn("graphicsLayer", f"ожидался {expected_gl}, но в записи оба значения, тумблер дёргали")
    else:
        r.fail("graphicsLayer", f"ожидался {expected_gl}, в логе {sorted(d['gl'])}")

    # 6. Отказ там, где он должен быть, и его отсутствие там, где не должен.
    if d["failure_offset"] is not None:
        if a.failure == "no":
            r.fail("отказ", f"отказа быть не должно, а он на {d['failure_offset']:.1f}-й секунде")
        else:
            r.ok("отказ", f"на {d['failure_offset']:.1f}-й секунде записи")
    elif a.failure == "certain":
        r.fail("отказ", "платформа обязана была отказать, а отказа нет, проверь конфигурацию")
    elif a.failure == "predicted":
        r.warn("отказ", "предсказанного отказа не случилось, это результат, а не брак прогона")
    else:
        r.ok("отказ", "нет, как и ожидалось")

    # 7. Метрики. Строка FPS пишется только тогда, когда частота разошлась с последней
    # записанной на FPS_LOG_DELTA и больше, а считать её начинают вместе с приложением,
    # то есть на этапе настройки. Поэтому ровный прогон законно даёт ноль строк FPS,
    # и это признак гладкости, а не поломки. Настоящие кадры смотрим в gfxinfo.
    if d["fps"]:
        r.ok("FPS (оверлей)", f"{len(d['fps'])} строк, {min(d['fps'])}..{max(d['fps'])} "
                              f"(пишется по дельте 5, мало строк - норма)")
    elif a.hot_logs == "off":
        r.note("FPS", "frameLoop погашен вместе с горячими логами, оверлей молчал, смотри gfxinfo")
    else:
        r.ok("FPS (оверлей)", "ни одной строки, частота за весь прогон не менялась заметно")
    if d["wakeups"]:
        wk = sorted(d["wakeups"])
        r.ok("пробуждения кадрового цикла", f"{len(wk)} замеров, {wk[0]}..{wk[-1]}/с, "
                                            f"медиана {wk[len(wk) // 2]} (это не FPS)")
    if not d["ram"]:
        r.warn("RAM в логе", "ни одной строки")
    else:
        r.ok("RAM в логе", f"{len(d['ram'])} строк, пик {max(d['ram'])}MB")

    # 8. gfxinfo, единственный источник по кадрам. Logcat их не считает.
    if a.gfxinfo:
        g = read_gfxinfo(a.gfxinfo)
        if not g:
            r.warn("gfxinfo", "файл есть, но счётчиков кадров в нём нет")
        else:
            r.ok("кадры (gfxinfo)", f"всего {g['total']}, janky {g['janky']} ({g['janky_pct']})")
            pct = ", ".join(f"{k}={v}" for k, v in g["percentiles"].items())
            r.note("перцентили кадра", pct)
    else:
        r.note("gfxinfo", "не передан, по кадрам судить не по чему")

    # 9. Culling и LOD в лог не пишутся, поэтому здесь только наблюдения, без вердикта.
    if d["sample_sizes"]:
        r.note("sampleSize", f"{sorted(d['sample_sizes'])}, при LOD=off ожидается одно значение")
    if d["needed"]:
        r.note("нужно тайлов", f"{min(d['needed'])}..{max(d['needed'])}, "
                               f"при culling=off ожидается постоянное число")
    elif a.hot_logs == "off" and a.mode == "Tiled":
        r.note("нужно тайлов", "строку updateTiles гасят выключенные горячие логи")

    # 10. Маршрут жеста. Спрашивается только с A/B-прогонов, потому что у них сценарий
    # фиксированный. Обычный прогон свободный, и требовать от него краёв зума не за что.
    if a.route:
        check_route(r, d, a)

    # 11. Состояние комплекта фиксируем в отчёте, потому что через месяц по одному логу
    # не вспомнить, выключены здесь горячие логи или прогон не удался.
    r.note("горячие логи", a.hot_logs)

    r.render()
    print()
    if r.failed:
        print("  ВЕРДИКТ: прогон непригоден, пересними.")
        return 1
    if any(s == "WARN" for s, _, _ in r.rows):
        print("  ВЕРДИКТ: прогон годится, но есть замечания выше.")
        return 0
    print("  ВЕРДИКТ: прогон пригоден.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
