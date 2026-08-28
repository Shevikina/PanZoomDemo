# Замеры производительности

> Замерять только на **release-сборке** на **реальном устройстве**.
>
> ```bash
> ./gradlew :app:assembleRelease
> adb install app/build/outputs/apk/release/app-release-unsigned.apk
> ```

| Метрика                     | Наивный (все OFF) | Tiling + GL OFF | Tiling + GL ON |
|-----------------------------|-------------------|-----------------|----------------|
| FPS avg                     |                   |                 |                |
| Jank frames/сек             |                   |                 |                |
| RAM пик, МБ                 |                   |                 |                |
| Рекомпозиций/сек под жестом |                   |                 |                |

---

## Сценарий A - Cold start + panic pan

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb`
**Образ:** `test_image_large.jpg` (~36 МБ)
**Конфигурация:** Tiling ✅  Culling ✅  LOD ✅  GraphicsLayer ✅
**Steady-state FPS:** 60–61 fps (по `frameLoop` в логе; JankStats даёт 1–3 семпла за сессию - нерепрезентативно для avg)

| Прогон      | Stem                                             | Jank events / max | RAM PSS max | RAM Native max | Pool hit-rate | Decode avg | Wait avg  | Примечание          |
|-------------|--------------------------------------------------|:-----------------:|:-----------:|:--------------:|:-------------:|:----------:|:---------:|---------------------|
| A1          | `A1_realme-RMX3371-Android-14_2026-07-03_110633` |       0 / -       |   254 MB    |     168 MB     |    95.7 %     |   32 ms    |   16 ms   | до фикса async init |
| A2          | `A2_realme-RMX3371-Android-14_2026-07-04_1310`   |       0 / -       |   260 MB    |     167 MB     |    97.1 %     |   34 ms    |   11 ms   | после фикса         |
| A3          | `A3_realme-RMX3371-Android-14_2026-07-04_1327`   |       0 / -       |   256 MB    |     164 MB     |    97.4 %     |   32 ms    |   10 ms   | после фикса         |
| **Медиана** | -                                                |     **0 / -**     | **256 MB**  |   **167 MB**   |  **97.1 %**   | **32 ms**  | **11 ms** |                     |

> **A1** снят до переноса `BitmapFactory.decodeFile` (overview) в IO-поток. 
>   Метрики пула/RAM/decode сопоставимы с A2–A3; 
>   FPS по JankStats нерелевантен из-за разреженной выборки (3 семпла, один из них cold-start артефакт). 
>   Jank=0 - фриз при инициализации не попал в окно семплирования.
> **A2–A3** - прогоны после фикса: 0 jank, FPS 59–60 fps по JankStats.

---

## Сценарий B - LOD sweep

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_large.jpg` (~36 МБ)
**Конфигурация:** Tiling ✅  Culling ✅  LOD ✅  GraphicsLayer ✅
**Steady-state FPS:** 58–60 fps (по `frameLoop`; JankStats 1 семпл за сессию)

| Прогон      | Stem                                           | Jank events / max | RAM PSS max | RAM Native max | Pool hit-rate | Decode avg |  Wait avg  | Примечание                        |
|-------------|------------------------------------------------|:-----------------:|:-----------:|:--------------:|:-------------:|:----------:|:----------:|-----------------------------------|
| B1          | `B1_realme-RMX3371-Android-14_2026-07-04_1338` |    6 / 44ms ⚠️    |   350 MB    |     219 MB     |    46.6 %     |   73 ms    |   412 ms   | сессия 95.7с, лишние LOD-переходы |
| B2          | `B2_realme-RMX3371-Android-14_2026-07-04_1348` |       0 / -       |   328 MB    |     202 MB     |    57.9 %     |   87 ms    |   344 ms   |                                   |
| B3          | `B3_realme-RMX3371-Android-14_2026-07-04_1357` |     2 / 38ms      |   326 MB    |     203 MB     |    66.3 %     |   71 ms    |   403 ms   |                                   |
| **Медиана** | -                                              |   **2 / 38ms**    | **328 MB**  |   **203 MB**   |  **57.9 %**   | **73 ms**  | **403 ms** |                                   |

> В сценарии B задействованы все 4 LOD-уровня: ss=1 (512×512), ss=2 (256×256), ss=4 (128×128), ss=8 (64×64). 
> Pool hit-rate закономерно ниже чем в A (4 класса буферов делят 96 слотов), 
> cold-start evict ~25 повторяется во всех прогонах - структурная особенность первого zoom-out после прогрева. J
> ank-события (<50ms) возникают на LOD-переходах при быстрых пинчах, double-frame нет.

---

## Сценарий C - Real usage

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_large.jpg` (~36 МБ)
**Конфигурация:** Tiling ✅  Culling ✅  LOD ✅  GraphicsLayer ✅
**Steady-state FPS:** 59 fps (JankStats, 1–2 семпла за сессию)

| Прогон      | Stem                                           | Jank events / max | RAM PSS max | RAM Native max | Pool hit-rate | Decode avg |  Wait avg  | Примечание                                         |
|-------------|------------------------------------------------|:-----------------:|:-----------:|:--------------:|:-------------:|:----------:|:----------:|----------------------------------------------------|
| C1          | `C1_realme-RMX3371-Android-14_2026-07-04_1406` |       0 / -       |   324 MB    |     199 MB     |    78.8 %     |   89 ms    |   390 ms   | ss=1,2; чистый прогон                              |
| C2          | `C2_realme-RMX3371-Android-14_2026-07-04_1434` |       0 / -       |   317 MB    |     199 MB     |    77.7 %     |   132 ms   |   586 ms   | ss=1,2,4,8; троттлинг во второй половине сессии ⚠️ |
| C3          | `C3_realme-RMX3371-Android-14_2026-07-04_1412` |     1 / 55ms      |   283 MB    |     178 MB     |    52.6 %     |   121 ms   |   954 ms   | ss=1,2,4,8; wait max=6203ms ❌, 50% загрузок ss=8   |
| **Медиана** | -                                              |     **0 / -**     | **317 MB**  |   **199 MB**   |  **77.7 %**   | **121 ms** | **586 ms** |                                                    |

> **C1** - эталонный прогон: умеренный zoom (ss=1,2), cold-start evict=0, все метрики в норме.
> **C2** - устройство охлаждено перед стартом (первая половина decode 97ms ≈ C1),
>   но интенсивная LOD-активность (788 тайлов, ss=1–8) разогревает CPU к середине сессии: вторая половина 173ms. 
>   Для данного устройства это реальная производительность сценария с полным LOD sweep.
> **C3** - wait max=6203ms из-за экстремального zoom-out (50% загрузок ss=8), затор декод-очереди. Decode/wait ⚠️ также частично из-за тепла (прогон в 14:12, без охлаждения).

---

## Сценарий E - Память-победа / GL=OFF

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_large.jpg` (~36 МБ)
**Конфигурация:** Tiling ✅  Culling ✅  LOD ✅  GraphicsLayer ❌
**Steady-state FPS:** 59 fps (JankStats, 1 семпл за сессию)

| Прогон | Stem                                           | Jank events / max | RAM PSS max | RAM Native max | Pool hit-rate | Decode avg | Wait avg | Примечание                 |
|--------|------------------------------------------------|:-----------------:|:-----------:|:--------------:|:-------------:|:----------:|:--------:|----------------------------|
| E1     | `E1_realme-RMX3371-Android-14_2026-07-04_1458` |       0 / -       |   309 MB    |     191 MB     |    67.7 %     |   55 ms    |  273 ms  | ss=1,2,4; pan + pinch фазы |

> **E1** - jank=0 несмотря на выключенный GL. 
>   `TiledImageViewerContent` достаточно дешёвый composable: 
>   Composition-цикл укладывается в 16ms даже при ~60 рекомпозициях/сек на жесте - dropped frames не возникает на Snapdragon 695. 
>   Реальная стоимость GL=OFF видна в **recompose counter** (logcat: `recompose[Content] #N` растёт синхронно с жестом) и CPU profiler, а не в JankStats.
> Дополнительные E-прогоны не снимались: результат однозначен, доказательная база graphicsLayer - Layout Inspector / recompose counter, не JankStats.

---

## Сценарий F - Culling=OFF

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_large.jpg` (~36 МБ)
**Конфигурация:** Tiling ✅  Culling ❌  LOD ✅  GraphicsLayer ✅
**Steady-state FPS:** 59 fps (JankStats, 1 семпл за сессию)

| Прогон      | Stem                                           | Jank events / max | RAM PSS max  | RAM Native max | Pool hit-rate | Decode avg |   Wait avg   | Примечание                   |
|-------------|------------------------------------------------|:-----------------:|:------------:|:--------------:|:-------------:|:----------:|:------------:|------------------------------|
| F1          | `F1_realme-RMX3371-Android-14_2026-07-04_1607` |       0 / -       |   2 052 MB   |    1 673 MB    |     1.0 %     |   119 ms   |   8 517 ms   | 63.9с, горизонтальный прогон |
| F2          | `F2_realme-RMX3371-Android-14_2026-07-04_1623` |       0 / -       |   2 135 MB   |    1 747 MB    |     1.0 %     |   126 ms   |   9 063 ms   | 73.9с, горизонтальный прогон |
| F3          | `F3_realme-RMX3371-Android-14_2026-07-04_1638` |       0 / -       |   2 012 MB   |    1 656 MB    |     1.0 %     |   120 ms   |   8 479 ms   | 63.6с, горизонтальный прогон |
| **Медиана** | -                                              |     **0 / -**     | **2 052 MB** |  **1 673 MB**  |   **1.0 %**   | **120 ms** | **8 517 ms** |                              |

> Без culling `updateTiles` при каждом вызове пытается загрузить все ~9 000 тайлов изображения (50k×50k / 512px). 
>   Три декодера физически не успевают - к 8-й секунде 512×512 слот пула пустеет навсегда (miss_empty +~150 тайлов каждые 5с), 
>   RAM растёт линейно до 2 ГБ+. Pool hit-rate 1% = прогрев пула полностью бесполезен.
> Jank=0 во всех прогонах: graphicsLayer удерживает UI гладким даже при 2 ГБ RAM и wait avg ~8.5с в очереди декодера.
> Сравнение с A-медианой: RAM ×8 (2052MB vs 256MB), wait avg ×775 (8517ms vs 11ms).

---

## Сценарий G - LOD=OFF

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_large.jpg` (~36 МБ)
**Конфигурация:** Tiling ✅  Culling ✅  LOD ❌  GraphicsLayer ✅
**Steady-state FPS:** 58–59 fps (JankStats, 1 семпл за сессию)

| Прогон      | Stem                                           | Jank events / max | RAM PSS max | RAM Native max | Pool hit-rate | Decode avg |  Wait avg  | Примечание                                                 |
|-------------|------------------------------------------------|:-----------------:|:-----------:|:--------------:|:-------------:|:----------:|:----------:|------------------------------------------------------------|
| G1          | `G1_realme-RMX3371-Android-14_2026-07-04_1739` |       0 / -       |   461 MB    |     293 MB     |    72.2 %     |   104 ms   |   994 ms   | первая/вторая половина decode: ~104/~104ms                 |
| G2          | `G2_realme-RMX3371-Android-14_2026-07-04_1838` |       0 / -       |   492 MB    |     272 MB     |    69.6 %     |   116 ms   |  1 621 ms  | первая/вторая: 104/128ms                                   |
| G3          | `G3_realme-RMX3371-Android-14_2026-07-04_1841` |    1 / 36ms ⚠️    |   400 MB    |     240 MB     |    82.6 %     |   136 ms   |   815 ms   | первая/вторая: 97/185ms; jank в зоне термальной деградации |
| **Медиана** | -                                              |     **0 / -**     | **461 MB**  |   **272 MB**   |  **72.2 %**   | **116 ms** | **994 ms** |                                                            |

> С LOD=OFF `inSampleSize` всегда остаётся на 1 (ss=1:100% тайлов во всех прогонах). 
>   При zoom-out до scale=0.1 viewport накрывает ~95 тайлов по 512×512px (1MB каждый) вместо 64×64px при LOD=ON (ss=8). 
>   Пул немедленно перекладывается под ss=1: прогретые ss=2/4/8 слоты вытесняются в первом же цикле.
> Термальная деградация внутри сессии структурна для этого сценария: интенсивные zoom-циклы прогревают CPU 
>   (~95 декодов ×100ms = ~10с непрерывной работы за цикл). 
>   Медленные зумы не помогают - `conflate()` работает на быстрый жест, медленный загружает промежуточные состояния и тромбит пул.
> Сравнение с A-медианой: RAM ×1.8 (461MB vs 256MB), decode ×3.6 (116ms vs 32ms), wait ×90 (994ms vs 11ms).

---

## Сценарий D0 - Naive FPS baseline

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_small.jpg` (~243 KB)
**Конфигурация:** Tiling ❌  (NaiveImageViewer, все тумблеры недоступны)
**Steady-state FPS:** 59 fps (JankStats, 1 семпл за сессию)

| Прогон | Stem                                           | Jank events / max | RAM PSS max | RAM Native max | Pool hit-rate | Decode avg | Wait avg | Примечание                   |
|--------|------------------------------------------------|:-----------------:|:-----------:|:--------------:|:-------------:|:----------:|:--------:|------------------------------|
| D0-1   | `D0_realme-RMX3371-Android-14_2026-07-04_1847` |       0 / -       |    90 MB    |     16 MB      |      н/д      |    н/д     |   н/д    | jank=0 - эффект не проявился |

> **D0-1** - jank=0 несмотря на NaiveImageViewer (pan/zoom state читается в Composition). 
>   `test_image_small.jpg` (243 KB) достаточно мал, чтобы Composition с recomposition 60x/сек укладывалась в 16ms - dropped frames не возникает на Snapdragon 695. 
>   Та же причина, что в E1.
> Демо-эффект («FPS проседает на зуме») не подтверждается JankStats для данного размера картинки. 
>   Решение - переосмыслить D0: либо использовать recompose counter в logcat, 
>   либо взять более тяжёлый asset. Работа отложена.

---

## Сценарий OOM - Naive baseline crash

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` @ `02197cb` + async init fix
**Образ:** `test_image_medium.jpg` (~13.2 МБ сжатый)
**Конфигурация:** Tiling ❌  (NaiveImageViewer)

| Прогон | Stem                                            | Exceptions | Bitmap size | Время до краша | Тип краша                                                 |
|--------|-------------------------------------------------|:----------:|:-----------:|:--------------:|-----------------------------------------------------------|
| OOM-1  | `OOM_realme-RMX3371-Android-14_2026-07-04_1855` |    7 ✅     | **256 МБ**  |   **~0.3 с**   | `Canvas: trying to draw too large(256000000bytes) bitmap` |

> Крах произошёл **не через OutOfMemoryError**, а через Canvas-лимит Android: 
>   `BitmapFactory.decodeFile` успешно выделил 256 МБ ARGB_8888 bitmap в heap, но при попытке отрисовки `BitmapPainter.onDraw` → `RecordingCanvas.throwIfCannotDraw` отклонил его как слишком большой для Draw API.
> Для доклада это сильнее чем OOM: «даже если RAM физически есть - Android Draw API не способен это отрисовать». `test_image_large.jpg` (50k×50k) при том же подходе потребовал бы ~10 ГБ - до Canvas-лимита дело бы не дошло, упало бы раньше на аллокации.
> Прогон снимается **один раз** (крах детерминированный).

---

## Сценарий OOM-LARGE - NaiveImageViewer с test_image_large.jpg

**Устройство:** realme RMX3371 (Snapdragon 695, Android 14)
**Ветка:** `draft-app` (debug-сборка; метрика RAM/крашей не зависит от release)
**Образ:** `test_image_large.jpg` (30 000×30 000 px, ~35 МБ сжатый → ~3.4 ГБ ARGB_8888)
**Конфигурация:** Tiling ❌  (NaiveImageViewer, `test_image_large.jpg` подан временным патчем в DemoScreen)
**Цель прогона:** проверить утверждение из доклада «для большого ассета OOM случился бы на аллокации»

| Прогон    | Stem                                           | Exceptions | Bitmap size  | Время до краша | Тип краша                                                          |
|-----------|------------------------------------------------|:----------:|:------------:|:--------------:|--------------------------------------------------------------------|
| OOM-LARGE | `realme-RMX3371-Android-14_2026-07-08_170945` |    2 ❌     | **~3.4 ГБ**  |   **< 0.1 с**  | `IllegalStateException: Failed to decode test_image_large.jpg`     |

> **Реальные размеры файла:** 30 000×30 000 px (не 50k×50k). Decoded ARGB_8888: 30 000 × 30 000 × 4 = 3 600 000 000 байт ≈ 3.4 ГБ.
>
> **Механизм краша — не OutOfMemoryError напрямую:**
>   `BitmapFactory.decodeStream` внутри перехватывает OOM и возвращает `null` вместо броска исключения.
>   Наш код `?: error("Failed to decode $assetName")` (NaiveImageViewer.kt:36) конвертирует `null` в `IllegalStateException`.
>   Compose перехватил его в `ComposeInternal` (Error was captured in composition), затем `AndroidRuntime` выдал FATAL EXCEPTION и процесс убит SIGKILL (SIG: 9).
>
> **GC-давление:** два `NullPointerException` в теге `callGcSupression` до краша — Android пытался высвободить память через GC suppression во время аллокации, но не смог.
>
> **RAM до краша:** PSS 105–129 МБ (замеры до попытки аллокации, после краша данных нет).
>
> **Вывод для доклада:** утверждение «упало бы на аллокации» — **факт**, но тип исключения нужно уточнить.
>   Говорить не «OutOfMemoryError», а: «`BitmapFactory` вернул null, потому что 3.4 ГБ выделить невозможно, и приложение немедленно упало».
>   Числа в докладе скорректировать: 30k×30k → ~3.4 ГБ (не 50k×50k → ~10 ГБ).
>
> Прогон снимается **один раз** (крах детерминированный).
