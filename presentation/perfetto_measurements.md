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

## Сценарий E — GL=OFF (ожидается)

> Трейс не снят. Снять: `./scripts/startperf.sh E1` во время pinch-фазы сценария E.
>
> **Что сравнивать с A:**
> - `Recomposer:recompose` count и rate — ключевой показатель для слайда graphicsLayer
> - App Deadline Missed — появятся ли дропы без GL?
> - `Choreographer#doFrame` avg/max — вырастет ли main thread нагрузка?

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
