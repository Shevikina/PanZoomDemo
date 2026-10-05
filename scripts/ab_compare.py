#!/usr/bin/env python3
"""Сравнение двух прогонов одного кейса на разных сборках.

Отвечает на два вопроса, и порядок важен.

1. Годно ли сравнение вообще. Два прогона на телефоне в разном состоянии памяти
   несравнимы. Признаком служит медиана decodeTile, потому что это чистая
   CPU-работа внутри BitmapRegionDecoder, одинаковая в обеих сборках. Если она
   разошлась, то разошлось и состояние устройства, и кадры сравнивать бессмысленно.

   Признак работает, пока правка не трогает сам декод. Правка пирамиды тайлов его
   трогает, потому что меняет и размер региона, и inSampleSize. Для таких правок
   признак снимается флагом --decode-changed, и состояние устройства подтверждают
   тогда только MemAvailable и лоу-мем.
2. Что с кадрами, памятью и шумом в логе, если сравнение годно.

Понимает и текстовый logcat (`-v threadtime`), и JSON-выгрузку из Android Studio.

Возвращает 0, когда сравнение годное, и 2, когда недействительное.
"""

import argparse
import json
import re
import statistics
import sys
from datetime import datetime, timedelta

RE_TS = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})")

RE_FPS = re.compile(r"\bFPS:\s*(\d+)")
RE_WAKEUPS = re.compile(r"frameLoop: wakeups=(\d+)/s")
RE_RAM_N = re.compile(r"RAM N:\s*(\d+)MB")
RE_RAM_PSS = re.compile(r"RAM PSS:\s*(\d+)MB")
RE_DECODE = re.compile(r"decodeTile: .*?decode=(\d+)ms")
RE_ACTIVE = re.compile(r"\bactive=(\d+)")
# Вендорный счётчик пропущенных кадров realme и oplus. Строка выглядит так.
# Skipped: true 9 cost 157.8
RE_SKIPPED = re.compile(r"Skipped:\s*\w+\s+(\d+)\s+cost\s+([\d.]+)")
RE_DAVEY = re.compile(r"Davey! duration=(\d+)ms")
RE_RECYCLED = re.compile(r"on a recycle\(\)'d bitmap")
# Системный лоу-мем киллер. У oplus он зовётся OsenseKillAction, в AOSP lowmemorykiller.
RE_LOWMEM = re.compile(r"KillAction: \[atomic clean\] start|lowmemorykiller")

RE_GFX_TOTAL = re.compile(r"Total frames rendered:\s*(\d+)")
RE_GFX_JANKY = re.compile(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)")
RE_GFX_PCT = re.compile(r"(\d+)th percentile:\s*(\d+)ms")

# Пороги годности. Оба про состояние устройства, а не про код.
DECODE_DRIFT_PCT = 25
MEM_DRIFT_MB = 150

# Во сколько раз свободная память должна превышать пик прогона, чтобы разница в ней
# перестала что-то значить.
#
# MEM_DRIFT_MB задан абсолютным числом, а на устройстве с большой памятью та же
# разница составляет считаные проценты. Проверка отвечает на вопрос, близко ли прогон
# к краю, а не одинаково ли далеко он от него.
MEM_HEADROOM_FACTOR = 2

# Ширина колонки и длина разделителя. Значения стартовые, потому что подписи это
# имена комплектов, и fit_columns() пересчитывает ширину под фактические.
COL = 24
SEP = 78


def read_lines(path):
    """Строки лога как (смещение в секундах, текст). Формат определяется по первому байту."""
    with open(path, encoding="utf-8", errors="replace") as f:
        head = f.read(1)
        f.seek(0)
        if head == "{":
            return _read_json(f)
        return _read_threadtime(f)


def _read_json(f):
    out = []
    for m in json.load(f).get("logcatMessages", []):
        header = m.get("header", {})
        stamp = header.get("timestamp", {})
        t = stamp.get("seconds", 0) + stamp.get("nanos", 0) / 1e9
        out.append((t, f"{header.get('tag', '')}: {m.get('message', '')}"))
    if not out:
        return []
    base = out[0][0]
    return [(t - base, text) for t, text in out]


def _read_threadtime(f):
    out, first = [], None
    for line in f:
        m = RE_TS.match(line)
        if m:
            mo, d, h, mi, sec, ms = (int(x) for x in m.groups())
            # Года в threadtime нет, поэтому фиксируем любой и разницу считаем в пределах суток.
            stamp = datetime(2000, mo, d, h, mi, sec, ms * 1000)
            if first is None:
                first = stamp
            delta = stamp - first
            if delta < timedelta(0):
                delta += timedelta(days=1)
            out.append((delta.total_seconds(), line))
        elif out:
            # Продолжение многострочного сообщения относим к времени предыдущей строки.
            out.append((out[-1][0], line))
    return out


def read_run(path):
    """Все метрики одного прогона, собранные в словарь по имени метрики."""
    lines = read_lines(path)
    d = dict(
        path=path,
        duration=lines[-1][0] if lines else 0.0,
        fps=[], wakeups=[], ram_n=[], ram_pss=[], decode=[], active=[], davey=[],
        skipped_frames=0, skipped_cost=0.0, recycled=0, lowmem=0,
    )
    for _, line in lines:
        for regex, key in (
            (RE_FPS, "fps"), (RE_WAKEUPS, "wakeups"), (RE_RAM_N, "ram_n"),
            (RE_RAM_PSS, "ram_pss"), (RE_DECODE, "decode"), (RE_ACTIVE, "active"),
            (RE_DAVEY, "davey"),
        ):
            m = regex.search(line)
            if m:
                d[key].append(int(m.group(1)))
        m = RE_SKIPPED.search(line)
        if m:
            d["skipped_frames"] += int(m.group(1))
            d["skipped_cost"] += float(m.group(2))
        if RE_RECYCLED.search(line):
            d["recycled"] += 1
        if RE_LOWMEM.search(line):
            d["lowmem"] += 1
    return d


def read_gfxinfo(path):
    """Счётчики кадров из дампа `dumpsys gfxinfo`. None, если дампа нет."""
    if not path:
        return None
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return None
    total = RE_GFX_TOTAL.search(text)
    if not total:
        return None
    janky = RE_GFX_JANKY.search(text)
    return dict(
        total=int(total.group(1)),
        janky=int(janky.group(1)) if janky else 0,
        janky_pct=janky.group(2) if janky else "?",
        percentiles={m.group(1): m.group(2) for m in RE_GFX_PCT.finditer(text)},
    )


def read_meta(path):
    """Сайдкар от baseline.sh. В нём key=value, по строке на ключ."""
    if not path:
        return {}
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return {}
    meta = {}
    for line in text.splitlines():
        if "=" in line:
            key, _, value = line.partition("=")
            meta[key.strip()] = value.strip()
    return meta


def median(values):
    """Медиана списка. None для пустого списка."""
    return statistics.median(values) if values else None


def fmt(value, suffix=""):
    """Значение для колонки отчёта. Дробные округляются до целых."""
    if value is None:
        return "нет"
    if isinstance(value, float):
        return f"{value:.0f}{suffix}"
    return f"{value}{suffix}"


def row(label, a, b, note=""):
    """Печатает строку отчёта по колонкам текущей ширины."""
    print(f"  {label:<26}{a:<{COL}}{b:<{COL}}{note}")


def fit_columns(label_a, label_b):
    """Подгоняет ширину колонок под подписи, чтобы длинные имена не слипались."""
    global COL, SEP
    COL = max(len(label_a), len(label_b), 22) + 2
    SEP = 28 + COL * 2


def drift_pct(a, b):
    """Насколько b отличается от a, в процентах от a."""
    if not a or not b:
        return None
    return (b - a) / a * 100


def main():
    p = argparse.ArgumentParser(description="Сравнение двух прогонов одного кейса")
    p.add_argument("log_a")
    p.add_argument("log_b")
    p.add_argument("--label-a", default="A")
    p.add_argument("--label-b", default="B")
    p.add_argument("--gfxinfo-a")
    p.add_argument("--gfxinfo-b")
    p.add_argument("--meta-a")
    p.add_argument("--meta-b")
    p.add_argument("--decode-changed", action="store_true",
                   help="правка меняет сам декод, не считать расхождение по нему "
                        "признаком состояния устройства")
    p.add_argument("--noise", action="store_true",
                   help="это два прогона одной сборки, печатать разброс, а не вердикт A/B")
    a = p.parse_args()

    ra, rb = read_run(a.log_a), read_run(a.log_b)
    ga, gb = read_gfxinfo(a.gfxinfo_a), read_gfxinfo(a.gfxinfo_b)
    ma, mb = read_meta(a.meta_a), read_meta(a.meta_b)

    fit_columns(a.label_a, a.label_b)
    print()
    print("═" * SEP)
    row("", a.label_a, a.label_b)
    print("─" * SEP)
    row("сборка", ma.get("sha", "?"), mb.get("sha", "?"))
    row("длительность", fmt(ra["duration"], " s"), fmt(rb["duration"], " s"))

    print()
    print("ГОДНОСТЬ СРАВНЕНИЯ")
    verdict_ok = True

    # Флаги наблюдения. Горячие логи это Log.d на каждый кадр, R8 это другой байт-код.
    # Прогон с одним их набором против прогона с другим сравнивает не то, что кажется,
    # поэтому расхождение здесь сразу отменяет сравнение.
    #
    # Грязное рабочее дерево проверяется отдельно, потому что sha у двух таких прогонов
    # один, а бинари разные.
    if ma.get("dirty") == "yes" or mb.get("dirty") == "yes":
        note = "sha не опознаёт бинарь"
        if ma.get("sha") and ma.get("sha") == mb.get("sha"):
            note += ", коммит один, а сборки могли быть разные"
            verdict_ok = False
        row("грязное дерево", ma.get("dirty", "?"), mb.get("dirty", "?"), note)

    for key, label in (("hot_path_logs", "горячие логи"), ("r8", "R8")):
        va, vb = ma.get(key), mb.get(key)
        if va is None or vb is None:
            continue
        if va == vb:
            note = "ок"
        else:
            note = "РАСХОЖДЕНИЕ, наблюдение настроено по-разному"
            verdict_ok = False
        row(label, va, vb, note)

    da, db = median(ra["decode"]), median(rb["decode"])
    drift = drift_pct(da, db)
    if drift is None:
        note = "нет данных о декоде"
    elif a.decode_changed:
        note = f"{drift:+.0f}%, признак снят флагом --decode-changed"
    elif abs(drift) <= DECODE_DRIFT_PCT:
        note = f"ок ({drift:+.0f}%, порог {DECODE_DRIFT_PCT}%)"
    else:
        note = f"РАСХОЖДЕНИЕ {drift:+.0f}% при пороге {DECODE_DRIFT_PCT}%"
        verdict_ok = False
    row("медиана decode", fmt(da, " ms"), fmt(db, " ms"), note)

    mem_a = int(ma["mem_before_mb"]) if ma.get("mem_before_mb", "").isdigit() else None
    mem_b = int(mb["mem_before_mb"]) if mb.get("mem_before_mb", "").isdigit() else None
    if mem_a is not None and mem_b is not None:
        diff = abs(mem_a - mem_b)
        # Запас считаем по большему из двух пиков, потому что он и показывает, сколько
        # прогон реально просил у системы.
        peaks = [max(r["ram_pss"]) for r in (ra, rb) if r["ram_pss"]]
        peak = max(peaks) if peaks else None
        roomy = peak is not None and min(mem_a, mem_b) >= peak * MEM_HEADROOM_FACTOR
        if diff <= MEM_DRIFT_MB:
            note = f"ок (разница {diff} МБ, порог {MEM_DRIFT_MB})"
        elif roomy:
            note = (f"разница {diff} МБ, но свободного больше пика {peak} МБ "
                    f"в {MEM_HEADROOM_FACTOR}+ раза, до края далеко")
        else:
            note = f"РАСХОЖДЕНИЕ {diff} МБ при пороге {MEM_DRIFT_MB}, запас над пиком мал"
            verdict_ok = False
    else:
        note = "нет meta-файлов, проверить нечем"
    row("MemAvailable до", fmt(mem_a, " MB"), fmt(mem_b, " MB"), note)

    if ra["lowmem"] or rb["lowmem"]:
        verdict_ok = False
        note = "системный киллер работал во время записи"
    else:
        note = "ок"
    row("лоу-мем шторм", fmt(ra["lowmem"], " соб."), fmt(rb["lowmem"], " соб."), note)

    print()
    print("ЧТО ЗА СБОРКИ")
    # Бюджеты это код под испытанием, а не настройка замера, поэтому на вердикт они
    # не влияют. Ради их сравнения A/B обычно и затевают.
    for key, label in (("tile_cache_budget_mb", "бюджет кеша, МБ"),
                       ("bitmap_pool_budget_mb", "бюджет пула, МБ")):
        if key in ma or key in mb:
            row(label, ma.get(key, "нет"), mb.get(key, "нет"))

    print()
    print("КАДРЫ")
    if ga and gb:
        row("кадров (gfxinfo)", fmt(ga["total"]), fmt(gb["total"]))
        row("janky", f"{ga['janky']} ({ga['janky_pct']}%)", f"{gb['janky']} ({gb['janky_pct']}%)")
        for pct in ("90", "95", "99"):
            if pct in ga["percentiles"] or pct in gb["percentiles"]:
                row(f"{pct}th percentile",
                    ga["percentiles"].get(pct, "нет") + "ms",
                    gb["percentiles"].get(pct, "нет") + "ms")
    else:
        row("кадры (gfxinfo)", "нет", "нет", "дампа нет, смотри строки FPS")
    fa = f"{min(ra['fps'])}..{max(ra['fps'])}" if ra["fps"] else "без изменений"
    fb = f"{min(rb['fps'])}..{max(rb['fps'])}" if rb["fps"] else "без изменений"
    row("FPS (оверлей)", fa, fb, "пишется только при заметной смене, пусто значит ровно")
    row("пропущено кадров", fmt(ra["skipped_frames"]), fmt(rb["skipped_frames"]), "вендорный счётчик")
    row("их стоимость", fmt(ra["skipped_cost"] / 1000, " s"), fmt(rb["skipped_cost"] / 1000, " s"))
    row("Davey (>700ms)", fmt(len(ra["davey"])), fmt(len(rb["davey"])))

    print()
    print("ПАМЯТЬ И ТАЙЛЫ")
    row("пик RAM N", fmt(max(ra["ram_n"]) if ra["ram_n"] else None, " MB"),
        fmt(max(rb["ram_n"]) if rb["ram_n"] else None, " MB"))
    row("пик RAM PSS", fmt(max(ra["ram_pss"]) if ra["ram_pss"] else None, " MB"),
        fmt(max(rb["ram_pss"]) if rb["ram_pss"] else None, " MB"))
    row("пик active", fmt(max(ra["active"]) if ra["active"] else None),
        fmt(max(rb["active"]) if rb["active"] else None))
    row("декодов", fmt(len(ra["decode"])), fmt(len(rb["decode"])))
    row("пробуждений/с", fmt(median(ra["wakeups"])), fmt(median(rb["wakeups"])), "это не FPS")

    print()
    print("ШУМ В ЛОГЕ")
    row("recycle()'d bitmap", fmt(ra["recycled"]), fmt(rb["recycled"]),
        "должно быть 0, строки пишутся в кадровом колбэке")

    print()
    print("═" * SEP)
    if a.noise:
        # Одна и та же сборка, поэтому всё разошедшееся это собственный разброс замера.
        # Он же порог значимости, потому что меньшая разница о регрессе не говорит.
        print("Это ДВА ПРОГОНА ОДНОЙ СБОРКИ, а не A/B.")
        print("Всё, что разошлось выше, это собственный разброс замера. Разница между")
        print("сборками должна быть заметно больше этой, иначе она ничего не значит.")
    elif verdict_ok:
        print("Сравнение годное, устройство было в сопоставимом состоянии.")
        if a.decode_changed:
            print("Признак по декоду снят флагом, поэтому состояние подтверждают только")
            print("MemAvailable и лоу-мем. Смотри на них внимательнее обычного.")
    else:
        print("СРАВНЕНИЕ НЕДЕЙСТВИТЕЛЬНО, устройство было в разном состоянии.")
        print("Перезагрузи телефон и переснимай оба прогона, разница по кадрам ничего не значит.")
    print()
    return 0 if verdict_ok or a.noise else 2


if __name__ == "__main__":
    sys.exit(main())
