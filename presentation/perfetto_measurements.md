# Perfetto — замеры и выводы

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Инструмент:** `actual_frame_timeline_slice` (SurfaceFlinger) + `Trace.beginSection` app-секции
**Конфиг:** `startperf.sh` (atrace_categories: app/view/am + atrace_apps + ftrace/print)

> **Замечание по `decodeTile.wait` в Perfetto:** значения раздуты из-за взаимодействия
> `Trace.beginSection`/`endSection` с корутинами — секция остаётся открытой на треде при
> suspend/resume корутины на другом потоке. Для wait-времени доверять logcat-значениям
> из `presentation/logs_measurements.md`.

---

## Сценарий A — Cold start + panic pan (GL=ON)

**Трейс:** `A3_realme-RMX3371-Android-14_2026-07-04_2013.pb`
**Конфигурация:** Tiling ✅ Culling ✅ LOD ✅ GraphicsLayer ✅

### Frames (SurfaceFlinger)

| Метрика                 | Значение                       |
|-------------------------|--------------------------------|
| Всего фреймов           | 474                            |
| smooth                  | 434 (91.6%)                    |
| Buffer Stuffing         | 36 (7.6%) — on_time=1, не дроп |
| **App Deadline Missed** | **4 (0.8%)**                   |
| late (on_time=0)        | 6 (1.3%)                       |
| avg frame dur           | 6.49 ms                        |
| max frame dur           | 23.29 ms                       |

### Custom slices (Trace.beginSection)

| Секция              | count |        avg |         max |                      total |
|---------------------|------:|-----------:|------------:|---------------------------:|
| `updateTiles`       |   442 |    0.19 ms |     2.32 ms |                      82 ms |
| `flushPendingTiles` |   104 |    0.01 ms |     0.11 ms |                       1 ms |
| `decodeTile.decode` |   143 |   60.65 ms |   117.96 ms |                      8.6 s |
| `decodeTile.wait`   |   140 | ~~348 ms~~ | ~~9050 ms~~ | — (недостоверно, см. выше) |

### Recompositions

| Имя                    |    count |
|------------------------|---------:|
| `Recomposer:recompose` |      904 |
| `Recomposer:animation` |      896 |
| `Compose:recompose`    |       30 |
| **Итого**              | **1830** |

Rate: **122.6/sec**

896 `Recomposer:animation` совпадают с числом кадров Choreographer (896) — одна
animation-recompose на каждый кадр на протяжении всей сессии (tile fade-in или другой
`animate*` в дереве).

904 `Recomposer:recompose` ≈ 60/sec — **recompose-циклы синхронны с жестом.**
С GL=ON ожидалось бы их отсутствие при пане, но они есть. Нужно сравнение с E (GL=OFF).

### Main thread top

| Слайс                                         | count |     avg |      max |   total |
|-----------------------------------------------|------:|--------:|---------:|--------:|
| `Choreographer#doFrame`                       |   896 | 1.71 ms | 12.17 ms | 1472 ms |
| `checkForSemanticsChanges`                    |   134 | 0.60 ms |  2.70 ms |   80 ms |
| `deliverInputEvent`                           |    50 | 1.25 ms |  3.52 ms |   62 ms |
| `getAllUncoveredSemanticsNodesToIntObjectMap` |   127 | 0.32 ms |  1.24 ms |   40 ms |
| `Choreographer#onVsync`                       |   896 | 0.04 ms |  0.34 ms |   36 ms |

### Выводы

- **Main thread очень чист:** avg doFrame = 1.71ms из 16.7ms бюджета (10%). ✅
- **`updateTiles` не блокирует кадр:** 0.19ms avg — безопасно вызывать на main thread. ✅
- **`decodeTile.decode` строго async:** 60ms avg на IO-потоке, главный поток не затронут. ✅
- **4 App Deadline Missed** из 474 (0.8%) — приемлемо для panic-pan с первого холодного старта. ✅
- **Semantics overhead:** `checkForSemanticsChanges` + `getAllUncoveredSemantics...` суммарно
  ~120ms = 8% main-thread времени. Можно отключить accessibility semantics для prod (не цель демо).
- **Recompose при GL=ON:** 904 recompose/сессию ≈ 60/sec — неожиданно. Требует сравнения
  с E-трейсом (GL=OFF) чтобы понять, даёт ли graphicsLayer разницу.

---

## Сценарий E — GL=OFF

**Трейс:** `E_realme-RMX3371-Android-14_2026-07-04_2035.pb`
**Конфигурация:** Tiling ✅ Culling ✅ LOD ✅ GraphicsLayer ❌

### Frames (SurfaceFlinger)

| Метрика | Значение |
|---------|----------|
| Всего фреймов | 411 |
| smooth | 364 (88.6%) |
| Buffer Stuffing | 41 (10.0%) — on_time=1, не дроп |
| **App Deadline Missed** | **4 (1.0%)** |
| Display HAL, App Deadline Missed | 2 (0.5%) |
| late (on_time=0) | 10 (2.4%) |
| avg frame dur | 7.75 ms |
| max frame dur | 28.98 ms |

### Custom slices (Trace.beginSection)

| Секция | count | avg | max | total |
|--------|------:|----:|----:|------:|
| `updateTiles` | 380 | 0.21 ms | 3.87 ms | 81 ms |
| `flushPendingTiles` | 103 | 0.01 ms | 0.04 ms | 1 ms |
| `decodeTile.decode` | 176 | 38.77 ms | 88.78 ms | 6.8 s |
| `decodeTile.wait` | 209 | ~~167 ms~~ | ~~5166 ms~~ | — (недостоверно) |

### Recompositions

| Имя | count |
|-----|------:|
| `Recomposer:recompose` | 903 |
| `Recomposer:animation` | 896 |
| `Compose:recompose` | 406 |
| **Итого** | **2205** |

Rate: **147.8/sec** | avg: **0.28ms** | total CPU: **621.6ms**

### Main thread top

| Слайс | count | avg | max | total |
|-------|------:|----:|----:|------:|
| `Choreographer#doFrame` | 896 | 1.98 ms | 14.04 ms | 1776 ms |
| `deliverInputEvent` | 44 | 1.47 ms | 3.89 ms | 65 ms |
| `checkForSemanticsChanges` | 31 | 1.37 ms | 4.39 ms | 42 ms |
| `Choreographer#onVsync` | 896 | 0.04 ms | 0.27 ms | 39 ms |

---

## A vs E — сравнение (graphicsLayer ON vs OFF)

| Метрика | A — GL=ON | E — GL=OFF | Δ |
|---------|:---------:|:----------:|:--|
| smooth | 434 (91.6%) | 364 (88.6%) | −3 п.п. |
| App Deadline Missed | 4 | 4 + 2 HAL | +2 ⚠️ |
| late frames | 6 (1.3%) | 10 (2.4%) | +67% |
| doFrame avg | 1.71 ms | 1.98 ms | **+16%** |
| doFrame max | 12.17 ms | 14.04 ms | +15% |
| doFrame total | 1472 ms | 1776 ms | **+21%** |
| `Recomposer:recompose` | 904 | 903 | ≈ одинаково |
| `Recomposer:animation` | 896 | 896 | = одинаково |
| **`Compose:recompose`** | **30** | **406** | **×13.5 ❌** |
| recompose avg duration | 0.14 ms | 0.28 ms | **×2** |
| **recompose total CPU** | **250 ms** | **621 ms** | **×2.5 ❌** |

### Выводы

- **`Compose:recompose` ×13.5:** с GL=OFF каждый жест тригерит recompose конкретных
  Composable-скоупов (те, что читают pan/zoom state напрямую). С GL=ON state читается
  только в graphicsLayer-лямбде (Draw phase) — Composition не затронута. ✅

- **Recompose total CPU ×2.5 (250ms → 621ms):** graphicsLayer экономит 370ms CPU
  на recompose за 15-секундную сессию. На Snapdragon 695 с простым composable это не
  дропает кадры, но означает тепло + расход батареи + меньший запас до jank. ✅

- **doFrame +16%** (1.71 → 1.98ms): main thread на каждом кадре тратит на 0.27ms больше.
  Незаметно на этом железе, но на более слабом устройстве или с более тяжёлым composable
  это часть бюджета 16.7ms. ✅

- **App Deadline Missed одинаковый (4):** JankStats не покажет разницу между GL=ON и OFF —
  Perfetto с `Compose:recompose` и total recompose CPU является единственным инструментом
  доказательства для этого доклада.

- **Для слайда:** «JankStats не видит разницы. Perfetto видит: recompose-работы в 2.5 раза
  больше. На сложном composable, на слабом железе — это dropped frames. Здесь — CPU overhead
  и тепло. graphicsLayer — universally correct паттерн.»

---

## Технические заметки

### Buffer Stuffing

`Buffer Stuffing` (36 фреймов, 7.6%) означает, что приложение отрендерило кадр, но
SurfaceFlinger обнаружил очередь буферов > 1. `on_time_finish=1` у всех — кадр зрителю
доставлен вовремя. Не является dropped frame. Типично при burst-рендеринге в начале сессии.

### decodeTile.wait vs logcat

Логкат (A2/A3): wait avg=10–11ms. Perfetto: ~~348ms~~.
Причина: `Trace.beginSection("decodeTile.wait")` стартует на IO-потоке, затем корутина
suspended (ждёт семафор или dispatcher). Thread освобождается, `endSection` приходит от
другой корутины на том же треде — секция закрывается в произвольный момент.
Perfetto-значения wait — артефакт, не реальность.

### Recompose nomenclature

- `Recomposer:recompose` — фактический recompose-цикл Compose Runtime
- `Recomposer:animation` — кадр анимации (AnimationState/Animatable/animate*)
- `Compose:recompose` — точечный recompose малого scope (InvalidationResult)
