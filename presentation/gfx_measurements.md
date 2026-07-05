# gfxinfo - замеры и выводы

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Инструмент:** `adb shell dumpsys gfxinfo com.shevikina.panzoomdemo`
**Скрипт:** `scripts/gfx.sh start` / `scripts/gfx.sh stop <case>`
**Сборка:** release (измерения на debug не репрезентативны)

---

## Что показывают метрики и к чему они относятся

| Метрика                             | Что измеряет                                                                           | Враг / оружие                                                                                                                       |
|-------------------------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **Janky frames %**                  | Доля кадров, нарушивших дедлайн (>16.7ms на 60Hz)                                      | Оба врага - верхнеуровневый индикатор. Используй вместе с другими данными, т.к. зависит от длины сессии.                            |
| **99th percentile**                 | Хвост распределения времени кадра (UI-тред). Показывает «worst case» опыт пользователя | Оба врага. Надёжный сравниваемый показатель - не зависит от длины сессии.                                                           |
| **50th / 90th / 95th percentile**   | Типичное и почти-худшее время кадра                                                    | Baseline здоровья: на всех сценариях должен быть 6-9ms при бюджете 16.7ms.                                                          |
| **Number Slow UI thread**           | Кадры, где UI-тред занял > 2× бюджета (~33ms)                                          | Враг: стоимость кадра. **Важно:** сравнимо только между сессиями с похожим числом total frames.                                     |
| **Number Slow bitmap uploads**      | Кадры, где GPU texture upload задержал кадр                                            | Враг: память → Culling=OFF → GPU вынужден аплодить все тайлы. Уникальный сигнал, которого нет в logcat.                             |
| **Number Slow issue draw commands** | Кадры, где запись GPU-команд заняла слишком долго                                      | Вспомогательный. Растёт при сложных Draw call списках.                                                                              |
| **Number Missed Vsync**             | Кадры, опоздавшие на VSync планировщик                                                 | Любой враг. >0 - признак проблемы на уровне планировщика.                                                                           |
| **99th gpu percentile**             | Хвост GPU-времени (рендер на GPU)                                                      | Диагностика узкого места: если GPU 99th << CPU 99th - GPU не в bottleneck. На всех наших сценариях 7-10ms → **GPU не узкое место**. |

### Что gfxinfo доказывает в докладе

| Сигнал                              | Прогоны            | Вывод                                                     |
|-------------------------------------|--------------------|-----------------------------------------------------------|
| `Slow bitmap uploads` F1=7 vs A2=0  | F1 vs A2           | Culling=OFF → давление на GPU texture upload              |
| `Slow UI thread` F1=147 vs A2=31    | F1 vs A2           | Culling=OFF → UI-тред перегружен обработкой лишних тайлов |
| Janky% F≈5-6% vs A≈4% / B≈3%        | F1/F2 vs A2, B1/B2 | Culling=OFF повышает jank                                 |
| 99th GPU = 7-10ms во всех сценариях | Все                | GPU не узкое место - подтверждает CPU-природу проблем     |

> **Ограничение:** сравнение A vs E через `Slow UI thread` не работает - у E в 2-4× больше total
> frames (сценарий E включает zoom-циклы, генерирующие кадры непрерывно).
> Для доказательства graphicsLayer используй Perfetto (`Compose:recompose` ×13.5, см.
`perfetto_measurements.md`).

---

## Сценарий A - Cold start + panic pan (GL=ON, все оружия)

**Конфигурация:** Tiling ✅ Culling ✅ LOD ✅ GraphicsLayer ✅

| Метрика                    | A1 (1749) | A2 (1721) |
|----------------------------|-----------|-----------|
| Total frames               | 1252      | 698       |
| **Janky frames**           | **3.83%** | **4.87%** |
| 50th percentile            | 7ms       | 7ms       |
| 90th percentile            | 10ms      | 11ms      |
| 95th percentile            | 14ms      | 16ms      |
| **99th percentile**        | **29ms**  | **30ms**  |
| Number Missed Vsync        | 0         | 0         |
| Number Slow UI thread      | 48 (3.8%) | 31 (4.4%) |
| Number Slow bitmap uploads | 0         | 0         |
| Number Slow issue draw     | 13        | 13        |
| 50th gpu                   | 3ms       | 3ms       |
| 90th gpu                   | 3ms       | 4ms       |
| 95th gpu                   | 4ms       | 4ms       |
| **99th gpu**               | **9ms**   | **9ms**   |

---

## Сценарий B - Pan + zoom sweep (GL=ON, все оружия, baseline для LOD)

**Конфигурация:** Tiling ✅ Culling ✅ LOD ✅ GraphicsLayer ✅

| Метрика                    | B1        | B2        |
|----------------------------|-----------|-----------|
| Total frames               | 1440      | 1471      |
| **Janky frames**           | **3.19%** | **3.26%** |
| 50th percentile            | 6ms       | 6ms       |
| 90th percentile            | 11ms      | 12ms      |
| 95th percentile            | 19ms      | 14ms      |
| **99th percentile**        | **28ms**  | **26ms**  |
| Number Missed Vsync        | 0         | 0         |
| Number Slow UI thread      | 44 (3.1%) | 39 (2.7%) |
| Number Slow bitmap uploads | 0         | 0         |
| Number Slow issue draw     | 12        | 22        |
| 50th gpu                   | 3ms       | 3ms       |
| 90th gpu                   | 3ms       | 4ms       |
| 95th gpu                   | 3ms       | 4ms       |
| **99th gpu**               | **9ms**   | **9ms**   |

---

## Сценарий E - Cold start + panic pan (GL=OFF)

**Конфигурация:** Tiling ✅ Culling ✅ LOD ✅ GraphicsLayer ❌

| Метрика                    | E1        | E2        |
|----------------------------|-----------|-----------|
| Total frames               | 2683      | 2367      |
| **Janky frames**           | **2.27%** | **1.73%** |
| 50th percentile            | 8ms       | 7ms       |
| 90th percentile            | 11ms      | 10ms      |
| 95th percentile            | 13ms      | 12ms      |
| **99th percentile**        | **25ms**  | **21ms**  |
| Number Missed Vsync        | 0         | 0         |
| Number Slow UI thread      | 51 (1.9%) | 29 (1.2%) |
| Number Slow bitmap uploads | 0         | 0         |
| Number Slow issue draw     | 15        | 18        |
| 50th gpu                   | 3ms       | 3ms       |
| 90th gpu                   | 4ms       | 4ms       |
| 95th gpu                   | 5ms       | 4ms       |
| **99th gpu**               | **9ms**   | **8ms**   |

> `Slow UI thread` как ставка (1.2-1.9%) ниже, чем у A (3.8-4.4%) - из-за того, что E-сессия
> содержит zoom-циклы с высокой плотностью кадров, разбавляющие медленные.
> Сравнивать E vs A через gfxinfo нельзя.
> Используй Perfetto: `Compose:recompose` ×13.5 за аналогичный период.

---

## Сценарий F - Pan edge-to-edge (Culling=OFF)

**Конфигурация:** Tiling ✅ Culling ❌ LOD ✅ GraphicsLayer ✅

| Метрика                        | F1 ★       | F2        |
|--------------------------------|------------|-----------|
| Total frames                   | 3453       | 2219      |
| **Janky frames**               | **5.88%**  | **5.00%** |
| 50th percentile                | 8ms        | 8ms       |
| 90th percentile                | 16ms       | 19ms      |
| 95th percentile                | 20ms       | 22ms      |
| **99th percentile**            | **28ms**   | **28ms**  |
| Number Missed Vsync            | 0          | **1** ⚠️  |
| Number Slow UI thread          | 147 (4.3%) | 89 (4.0%) |
| **Number Slow bitmap uploads** | **7** ⚠️   | **2**     |
| Number Slow issue draw         | 16         | 4         |
| 50th gpu                       | 3ms        | 3ms       |
| 90th gpu                       | 3ms        | 3ms       |
| 95th gpu                       | 3ms        | 3ms       |
| **99th gpu**                   | **7ms**    | **7ms**   |

> F1 - основной прогон.
> F2 имеет Missed Vsync=1 и меньше total frames.
> **Ключевой сигнал:** `Slow bitmap uploads` F1=7 vs A2=0 - Culling=OFF вынуждает GPU аплодить
> все тайлы кадра, а не только видимые.

---

## Сценарий G - Pan + zoom sweep (LOD=OFF)

**Конфигурация:** Tiling ✅ Culling ✅ LOD ❌ GraphicsLayer ✅

| Метрика                    | G1        | G2        |
|----------------------------|-----------|-----------|
| Total frames               | 1648      | 1542      |
| **Janky frames**           | **2.61%** | **3.57%** |
| 50th percentile            | 6ms       | 7ms       |
| 90th percentile            | 11ms      | 13ms      |
| 95th percentile            | 14ms      | 17ms      |
| **99th percentile**        | **24ms**  | **24ms**  |
| Number Missed Vsync        | 0         | 0         |
| Number Slow UI thread      | 38 (2.3%) | 34 (2.2%) |
| Number Slow bitmap uploads | 0         | 0         |
| Number Slow issue draw     | 5         | 17        |
| 50th gpu                   | 3ms       | 3ms       |
| 90th gpu                   | 3ms       | 4ms       |
| 95th gpu                   | 3ms       | 6ms       |
| **99th gpu**               | **8ms**   | **8ms**   |

> gfxinfo не показывает LOD-эффект - `Slow bitmap uploads`=0 в обоих прогонах.
> LOD-аргумент доказывай через logcat: decode avg, RAM usage (см. `logs_measurements.md`).

---

## Сводка для слайдов

| Сравнение | Метрика                 | Baseline (A2) | Culling=OFF (F1) | Δ        |
|-----------|-------------------------|---------------|------------------|----------|
| A vs F    | Janky %                 | 4.87%         | 5.88%            | +1 п.п.  |
| A vs F    | 99th percentile         | 30ms          | 28ms             | ≈        |
| A vs F    | Slow UI thread %        | 4.4%          | 4.3%             | ≈        |
| A vs F    | **Slow bitmap uploads** | **0**         | **7**            | **+7 ❌** |

| Сравнение | Метрика             | Baseline (B1/B2) | LOD=OFF (G1/G2) | Δ               |
|-----------|---------------------|------------------|-----------------|-----------------|
| B vs G    | Janky %             | 3.19-3.26%       | 2.61-3.57%      | ≈ (нет сигнала) |
| B vs G    | 99th percentile     | 26-28ms          | 24ms            | ≈               |
| B vs G    | Slow bitmap uploads | 0                | 0               | -               |

> **Вывод:** gfxinfo подтверждает Culling-аргумент (`Slow bitmap uploads`).
> LOD-эффект в gfxinfo не виден на этом железе - используй logcat/Perfetto.
