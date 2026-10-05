#!/bin/bash
#
# Обвязка для снятия комплекта замеров.
#
# Автоматизировано всё, кроме жеста. Обвязка собирает release, ставит его, сбрасывает
# счётчики, пишет logcat, снимает gfxinfo, конвертирует в CSV и пересобирает README.
# Руками остаётся пан и пинч-зум на устройстве.
#
# Комплект определяется коммитом и флагами сборки, поэтому прогоны с разными
# настройками физически не попадают в один каталог.
#
#   ./scripts/baseline.sh doctor          # проверить окружение, устройство и флаги сборки
#   ./scripts/baseline.sh build           # собрать release и поставить на устройство
#   ./scripts/baseline.sh runs            # какие комплекты уже сняты
#   ./scripts/baseline.sh list            # чек-лист прогонов со статусами
#   ./scripts/baseline.sh run <case>      # один прогон
#   ./scripts/baseline.sh csv <case>      # переконвертировать уже снятый лог
#
set -uo pipefail

# Три разных корня, которые по умолчанию совпадают.
#   SCRIPT_ROOT  - где лежит сам скрипт и его питоновские помощники.
#   PROJECT_ROOT - что собирается и у чего спрашиваются флаги и коммит.
#   LOGS_ROOT    - куда складываются комплекты.
#
# Разводить их нужно для A/B со старой веткой. Там достаточно подменить PROJECT_ROOT,
# и тогда скрипт с логами остаются в основном чекауте, а собирается worktree, у которого
# своей копии этого скрипта нет.
SCRIPT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS_ROOT="${LOGS_ROOT:-$SCRIPT_ROOT}"

# Корень чекаута, в котором стоим. Пустой вывод и код 1, если это не чекаут проекта.
#
# Скрипт ветки-эталона запускают из её worktree, поэтому путь к скрипту и рабочий каталог
# указывают на разные деревья. Собирать надо то, в котором стоишь, и это рабочий каталог.
find_checkout_root() {
    local dir="$PWD"
    while [ "$dir" != "/" ]; do
        if [ -f "$dir/gradlew" ] && [ -f "$dir/app/build.gradle.kts" ]; then
            echo "$dir"
            return 0
        fi
        dir="$(dirname "$dir")"
    done
    return 1
}

if [ -n "${PROJECT_ROOT:-}" ]; then
    PROJECT_ROOT_SOURCE="переменная PROJECT_ROOT"
else
    CWD_ROOT="$(find_checkout_root)" || CWD_ROOT=""
    if [ -n "$CWD_ROOT" ] && [ "$CWD_ROOT" != "$SCRIPT_ROOT" ]; then
        PROJECT_ROOT="$CWD_ROOT"
        PROJECT_ROOT_SOURCE="рабочий каталог"
    else
        PROJECT_ROOT="$SCRIPT_ROOT"
        PROJECT_ROOT_SOURCE="каталог скрипта"
    fi
fi
PROJECT_ROOT="$(cd "$PROJECT_ROOT" 2>/dev/null && pwd)" || {
    echo "Ошибка: PROJECT_ROOT указывает в никуда." >&2; exit 1
}
[ -f "$PROJECT_ROOT/gradlew" ] && [ -f "$PROJECT_ROOT/app/build.gradle.kts" ] || {
    echo "Ошибка: в $PROJECT_ROOT нет gradlew и app/build.gradle.kts - это не чекаут проекта." >&2
    exit 1
}
APP="com.shevikina.panzoomdemo"
ACTIVITY="$APP/.MainActivity"
# Тип сборки. По умолчанию release, потому что числа снимаются на нём. Для A/B двух
# сборок годится и debug, потому что обе стороны одинаково debuggable.
BUILD_TYPE="${BUILD_TYPE:-release}"
case "$BUILD_TYPE" in
    release|debug) ;;
    *) echo "Ошибка: BUILD_TYPE может быть release или debug, а не '$BUILD_TYPE'." >&2; exit 1 ;;
esac

# ── adb и устройство ─────────────────────────────────────────────────────────
# Отсюда берутся ADB, prop и device_stem. Общий помощник, а не своя копия, чтобы имя
# устройства в именах файлов складывалось у всех скриптов одинаково.
source "$(dirname "$0")/device.sh"

# ── Состояние сборки ─────────────────────────────────────────────────────────
# Файлы, из которых читаются горячие логи, R8 и бюджеты. Эти настройки меняют цифры
# сильнее, чем сам сценарий, поэтому они попадают в имя каталога комплекта, и прогоны
# с разными настройками не смешиваются.
DEMO_LOG_KT="$PROJECT_ROOT/app/src/main/java/com/shevikina/panzoomdemo/core/log/DemoLog.kt"
BUILD_KTS="$PROJECT_ROOT/app/build.gradle.kts"
TILE_CACHE_KT="$PROJECT_ROOT/app/src/main/java/com/shevikina/panzoomdemo/presentation/demo/imageviewer/tiled/state/TileCache.kt"
BITMAP_POOL_KT="$PROJECT_ROOT/app/src/main/java/com/shevikina/panzoomdemo/data/datasource/BitmapPoolLocalDataSource.kt"

# Положение горячих логов для текущего типа сборки, то есть on, off или unknown.
#
# Значение задаёт сборка, а не правка кода, потому что в build.gradle.kts у каждого типа
# сборки свой buildConfigField HOT_LOGS. Разовое отклонение задаётся переменной
# HOT_LOGS_OVERRIDE, и cmd_build передаёт её в gradle тем же значением, чтобы имя
# каталога совпадало с тем, что реально собрано.
hot_logs_state() {
    case "${HOT_LOGS_OVERRIDE:-}" in
        true)  echo "on";      return ;;
        false) echo "off";     return ;;
        "")    ;;
        *)     echo "unknown"; return ;;
    esac

    # На ветках до переезда флага он лежит константой в DemoLog.kt. Ветка нужна для A/B
    # с такими ветками, потому что иначе на них не снять ни одного прогона.
    if [ -f "$DEMO_LOG_KT" ] && grep -q 'HOT_PATH_ENABLED' "$DEMO_LOG_KT"; then
        case "$(grep -Eo 'HOT_PATH_ENABLED = (true|false)' "$DEMO_LOG_KT" | head -1)" in
            *true)  echo "on";  return ;;
            *false) echo "off"; return ;;
        esac
    fi

    # Если файла сборки нет, то дерево не наше, и гадать не о чем.
    [ -f "$BUILD_KTS" ] || { echo "unknown"; return; }

    # Берём HOT_LOGS из блока нужного типа сборки. Ориентиром служит ближайший
    # предшествующий заголовок debug или release, поэтому одноимённый блок
    # в signingConfigs не путает.
    awk -v bt="$BUILD_TYPE" '
        /^[[:space:]]*(debug|release)[[:space:]]*\{/ { current = $1; gsub(/[^a-z]/, "", current) }
        /HOT_LOGS/ && current == bt {
            if (/getOrElse\(true\)/)  { print "on";  found = 1; exit }
            if (/getOrElse\(false\)/) { print "off"; found = 1; exit }
        }
        END { if (!found) print "unknown" }
    ' "$BUILD_KTS"
}

# Положение R8, то есть on, off или unknown.
r8_state() {
    # К debug шринк не применяется, что бы ни стояло в блоке release.
    [ "$BUILD_TYPE" = "debug" ] && { echo "off"; return; }
    [ -f "$BUILD_KTS" ] || { echo "unknown"; return; }
    grep -Eq '^[[:space:]]*isMinifyEnabled = true' "$BUILD_KTS" && echo "on" || echo "off"
}

# Число мегабайт из строки `const val DEFAULT_MAX_BYTES = NN L * 1024 * 1024`.
# Знак вопроса, если файла или строки нет.
#
# Такая константа сейчас есть только у пула буферов. У TileCache её нет, поэтому
# CACHE_MB выходит знаком вопроса и в имя каталога не попадает.
budget_mb() {
    [ -f "$1" ] || { echo "?"; return; }
    grep -Eo 'DEFAULT_MAX_BYTES = [0-9]+' "$1" | head -1 | grep -Eo '[0-9]+' || echo "?"
}

HOT_LOGS="$(hot_logs_state)"
R8="$(r8_state)"
CACHE_MB="$(budget_mb "$TILE_CACHE_KT")"
POOL_MB="$(budget_mb "$BITMAP_POOL_KT")"

# ── Каталог прогона ──────────────────────────────────────────────────────────
# Имя собирается из коммита и состояния флагов, поэтому смена любого из них уводит
# прогоны в новый каталог, а снятые остаются на месте.
SHA="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
BRANCH="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
# Пометка о незакоммиченных правках. Нужна, потому что коммит у двух таких прогонов
# один, а бинари после пересборки разные.
DIRTY="$(test -n "$(git -C "$PROJECT_ROOT" status --porcelain 2>/dev/null)" && echo yes || echo no)"
LEGACY_DIR="$LOGS_ROOT/logs/baseline_$SHA"
if [ -n "${BASELINE_DIR:-}" ]; then
    BASE_DIR="$BASELINE_DIR"
elif [ -d "$LEGACY_DIR" ] && [ "$BUILD_TYPE" = "release" ]; then
    # Каталог, снятый до появления флагов в имени. Дописываем в него, а не плодим второй.
    # Только для release, потому что debug-прогоны рядом с release смешали бы в одном
    # каталоге числа, которые сравнивать нельзя.
    BASE_DIR="$LEGACY_DIR"
else
    # Тип сборки попадает в имя только для debug, чтобы release-комплекты сохранили
    # прежние имена и уже снятые каталоги не переезжали.
    if [ "$BUILD_TYPE" = "debug" ]; then
        BASE_DIR="$LOGS_ROOT/logs/run_${SHA}_debug_hot-${HOT_LOGS}_r8-${R8}"
    else
        BASE_DIR="$LOGS_ROOT/logs/run_${SHA}_hot-${HOT_LOGS}_r8-${R8}"
    fi
fi
RAW_DIR="$BASE_DIR/raw"
CSV_DIR="$BASE_DIR/csv"
GFX_DIR="$BASE_DIR/gfxinfo"
REC_DIR="$BASE_DIR/records"
README="$BASE_DIR/README.md"
MANIFEST="$BASE_DIR/run.env"

# Читаемое имя комплекта для README и шапки прогона.
run_title() {
    case "$BASE_DIR" in
        *"/baseline_"*) echo "Комплект без манифеста" ;;
        *)
            # Ветку и коммит называем прямо, потому что комплект снимают с любой ветки,
            # и по одному заголовку иначе не понять, с какой.
            if [ "$HOT_LOGS" = "off" ]; then
                echo "Замер с выключенными горячими логами, пара к опорному: $BRANCH @ $SHA"
            else
                echo "Опорный прогон: $BRANCH @ $SHA"
            fi
            ;;
    esac
}

# Пишет манифест комплекта. По нему check_manifest и не даёт дописать в комплект
# прогон с другой сборкой.
write_manifest() {
    mkdir -p "$BASE_DIR"
    {
        echo "sha=$SHA"
        echo "commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "dirty=$DIRTY"
        echo "build_type=$BUILD_TYPE"
        echo "hot_path_logs=$HOT_LOGS"
        echo "r8=$R8"
        echo "tile_cache_budget_mb=$CACHE_MB"
        echo "bitmap_pool_budget_mb=$POOL_MB"
        echo "device=$(device_stem)"
        echo "created=$(date '+%Y-%m-%d %H:%M')"
    } > "$MANIFEST"
}

# Значение ключа из манифеста. Код 1, если манифеста нет.
manifest_value() {
    [ -f "$MANIFEST" ] || return 1
    grep -E "^$1=" "$MANIFEST" | head -1 | cut -d= -f2-
}

# Сверяет текущее состояние сборки с манифестом комплекта. Расхождение обрывает работу,
# несовпадение устройства печатает предупреждение.
check_manifest() {
    [ -f "$MANIFEST" ] || { write_manifest; return 0; }
    local key expected actual
    for key in sha build_type hot_path_logs r8; do
        expected="$(manifest_value "$key")"
        case "$key" in
            sha)            actual="$SHA" ;;
            build_type)     actual="$BUILD_TYPE" ;;
            hot_path_logs)  actual="$HOT_LOGS" ;;
            r8)             actual="$R8" ;;
        esac
        if [ -n "$expected" ] && [ "$expected" != "$actual" ]; then
            die "в $BASE_DIR уже лежат прогоны с $key=$expected, а сейчас $key=$actual.
     Смешивать их нельзя: цифры станут несравнимыми. Верни прежнее состояние сборки
     или сними комплект в новый каталог (он назовётся по коммиту и флагам сам)."
        fi
    done
    local dev_expected
    dev_expected="$(manifest_value device)"
    if [ -n "$dev_expected" ] && [ "$dev_expected" != "$(device_stem)" ]; then
        echo "ВНИМАНИЕ: комплект снят на $dev_expected, сейчас подключено $(device_stem)."
        echo "          Числа с разных устройств в одну таблицу не кладут."
    fi
    return 0
}

# ── Таблица прогонов ─────────────────────────────────────────────────────────
# Кейсы чек-листа. Буквы в имени это включённые тумблеры, где c означает culling,
# l означает LOD, а g означает graphicsLayer. Имя tiled-off означает, что выключены
# все три.
#
# Наивный режим на MEDIUM и LARGE это прогоны на отказ. Платформа такой битмап рисовать
# отказывается, вьюер ловит отказ и возвращает режим в тайловый, поэтому с этих прогонов
# снимаются время до отказа и пик RAM, а не кадры. Установившийся наивный режим есть
# только на SMALL, отсюда отдельный кейс naive-small.
#
# У tiled-lg-large и tiled-cg-large погашено ровно одно оружие, а не все сразу. Только
# так и виден вклад этого оружия, потому что в tiled-off выключено всё и вклад отдельного
# тумблера из него не достать.
CASES="naive-small \
naive-medium tiled-off-medium tiled-cl-medium tiled-clg-medium \
naive-large tiled-off-large tiled-c-large tiled-cl-large tiled-clg-large \
tiled-lg-large tiled-cg-large"

# ── A/B двух сборок ──────────────────────────────────────────────────────────
# Кейсы, которых хватает, чтобы отличить регресс в коде от состояния телефона.
# Главный среди них tiled-cl-large, потому что culling держит память в узде, устройство
# не уходит в своп, и кадры меряют стоимость кадра, а не давление на память.
AB_CASES="tiled-cl-large tiled-off-large tiled-clg-large naive-small"

# Длительность A/B-записи в секундах. Фиксированная, потому что две сборки сравнивают
# только на прогонах одинаковой длины.
AB_DURATION="${AB_DURATION:-90}"

# Сколько дать системе устаканиться после загрузки, прежде чем писать лог.
AB_SETTLE_S="${AB_SETTLE_S:-60}"

# Аптайм в секундах, выше которого телефон считается не готовым к прогону. Память к тому
# времени занята фоном, и второй сборке достанется не то же состояние, что первой.
AB_MAX_UPTIME_S="${AB_MAX_UPTIME_S:-1200}"

# Размер кольцевого буфера logd на время прогона.
#
# Поднимаем, потому что по умолчанию буфер вмещает лишь несколько секунд записи, а больше
# половины строк в нём чужие и место занимают наравне с нашими. Переполнение строки не
# задерживает, а затирает, поэтому в файл попадала бы только часть отсчёта.
#
# Размер сбрасывается перезагрузкой, а скрипт перезагружает телефон перед прогоном,
# поэтому ставим его каждый раз заново.
LOG_BUFFER_SIZE="${LOG_BUFFER_SIZE:-16M}"

# Подаёт звуковой сигнал [$1] раз. Нужен на границе фаз, потому что следить за строкой
# отсчёта и одновременно держать телефон двумя руками неудобно, а пропущенный переход
# срывает маршрут.
#
# Гасится переменной AB_BELL=off. Своя команда ставится в AB_BELL_CMD, например
# AB_BELL_CMD="afplay /System/Library/Sounds/Tink.aiff".
ab_bell() {
    [ "${AB_BELL:-on}" = "on" ] || return 0
    local n="${1:-1}"
    while [ "$n" -gt 0 ]; do
        if [ -n "${AB_BELL_CMD:-}" ]; then
            eval "$AB_BELL_CMD" >/dev/null 2>&1 &
        elif command -v afplay >/dev/null 2>&1 && [ -f /System/Library/Sounds/Tink.aiff ]; then
            afplay /System/Library/Sounds/Tink.aiff >/dev/null 2>&1 &
        else
            printf '\a'
        fi
        n=$((n - 1))
        [ "$n" -gt 0 ] && sleep 0.25
    done
    return 0
}

# Спрашивает про перезагрузку с обратным отсчётом. Код 0 означает согласие.
#
# Молчание считается согласием, потому что телефон перед прогоном чаще всего надо
# перезагрузить. Отказ ловится одной клавишей, без Enter, чтобы успеть за отсчёт.
# Когда ввода нет вовсе, например при запуске из другого скрипта, берётся умолчание.
ab_ask_reboot() {
    local left="${AB_CONFIRM_S:-30}" answer=""
    if [ ! -t 0 ]; then
        echo "Перезагрузить сейчас? Ввода нет, перезагружаю."
        return 0
    fi
    while [ "$left" -gt 0 ]; do
        printf "\rПерезагрузить? [Y/n или т], через %2dс перезагружу сама: \033[K" "$left"
        read -t 1 -n 1 -r answer && break
        left=$((left - 1))
    done
    printf "\n"
    # Буквы т и Т это клавиша n на раскладке ЙЦУКЕН. Без них отказ русской раскладкой
    # уходил бы в ветку согласия.
    case "$answer" in
        n | N | т | Т) return 1 ;;
        *)             return 0 ;;
    esac
}

# Путь к самому свежему APK текущего типа сборки. Пустой вывод, если его нет.
built_apk() {
    ls -t "$PROJECT_ROOT/app/build/outputs/apk/$BUILD_TYPE"/*.apk 2>/dev/null | head -1
}

# Первый найденный исходник, который новее файла [$1]. Пустой вывод означает, что сборка
# не отстала.
#
# Сравниваем с исходниками, а не с моментом запуска сборки, потому что gradle имеет право
# ничего не делать, и APK тогда честно остаётся старым.
sources_newer_than() {
    # Фильтр -type f нужен, потому что каталог меняет время вместе с любым файлом внутри,
    # и в сообщении об ошибке оказался бы каталог вместо имени файла.
    find "$PROJECT_ROOT/app/src" \
         "$PROJECT_ROOT/app/build.gradle.kts" \
         "$PROJECT_ROOT/gradle/libs.versions.toml" \
         -type f -newer "$1" -print 2>/dev/null | head -1
}

# Проверяет, что APK собран после последней правки исходников. Расхождение обрывает
# работу, чтобы прогон не ушёл на устаревшую сборку.
check_apk_fresh() {
    local apk stale
    apk="$(built_apk)"
    [ -n "$apk" ] || die "APK для $BUILD_TYPE не собран. ./scripts/baseline.sh build"
    stale="$(sources_newer_than "$apk")"
    [ -z "$stale" ] || die "APK старше исходников, например $stale
     APK собран $(date -r "$apk" '+%Y-%m-%d %H:%M'). Пересобери: ./scripts/baseline.sh build"
}

# Поднимает буфер logd, чтобы запись не затиралась на ходу.
set_log_buffer() {
    "$ADB" logcat -G "$LOG_BUFFER_SIZE" >/dev/null 2>&1 ||
        echo "  Буфер logd поднять не удалось, запись может выйти неполной."
}

# Ассет, на котором делается настройка до записи. Самый маленький, потому что он живёт
# в любом режиме, и перед наивным режимом ассет надо увести на него, чтобы отказ
# не прилетел ещё до записи.
#
# Приложение восстанавливает режим, ассет и тумблеры после перезапуска, поэтому на что
# оно откроется, заранее не угадать. Настройку проверяют глазами.
SETUP_ASSET="SMALL"

# Признаки отказа в логе. Это либо краш процесса, либо перехваченный вьюером отказ
# платформы.
RE_FAILURE="FATAL EXCEPTION|OutOfMemoryError|Наивная (загрузка|отрисовка)"

# Тип прогона. Значение steady означает установившийся режим, где снимают кадры, джанк
# и RAM. Значение failure означает прогон на отказ, где снимают время до отказа и пик RAM.
case_kind() {
    case "$1" in
        naive-medium|naive-large) echo "failure" ;;
        *) echo "steady" ;;
    esac
}

# Режим, который надо выставить ДО начала записи.
case_setup_mode() {
    case "$1" in
        naive-small) echo "Tiled" ;;
        naive-*) echo "Naive" ;;
        tiled-*) echo "Tiled" ;;
    esac
}

# Положение culling. В лог оно не пишется, но от него зависит, видно ли в логе жест.
# При выключенном culling набор нужных тайлов постоянен, поэтому строка updateTiles после
# первой загрузки не пишется и масштаба в логе нет.
case_culling() {
    case "$1" in
        naive-*)     echo "n/a" ;;
        # У tiled-lg выключен culling, а LOD и graphicsLayer включены.
        tiled-off-*|tiled-lg-*) echo "off" ;;
        tiled-*)     echo "on" ;;
    esac
}

# Ожидаемое положение graphicsLayer, единственного тумблера, который виден в логе
# буквально. Его пишет строка recompose[Content] из TiledImageViewerContent.
# Значение n/a означает наивный режим, где тайлов нет вовсе.
case_gl() {
    case "$1" in
        naive-*)     echo "n/a" ;;
        tiled-clg-*|tiled-lg-*|tiled-cg-*) echo "on" ;;
        tiled-*)     echo "off" ;;
    esac
}

# Единственное действие внутри записи, с которого начинается целевая конфигурация.
case_action() {
    case "$1" in
        naive-small) echo "переключи режим на Naive" ;;
        *) echo "переключи ассет на $(case_asset "$1")" ;;
    esac
}

# Фазы A/B-записи, по строке «доля в процентах:подпись».
#
# Доли, а не секунды, чтобы сценарий пережил смену длительности. Набор один на все
# прогоны, потому что маршрут пальцем задаёт, какие уровни LOD вообще появятся в логе,
# и разошедшийся маршрут сравнивать не с чем.
#
# Доли выбраны по тому, где есть куда двигать палец. Ход пана ограничен клампом, который
# равен `viewport - imageWidth * scale`, поэтому на отдалённой картинке в край упираешься
# за один свайп, а на стартовом и приближённом масштабе ход большой. Отсюда и больше всего
# времени там, где палец не упирается и набор тайлов меняется на каждый свайп.
# Отдалённой хватает короткой фазы, потому что разница в числе тайлов возникает от самого
# факта отдаления, а не от того, сколько по ней возили.
#
# Приближение длиннее отдаления, потому что путь до упора это несколько щипков подряд,
# а обратный путь один.
#
# Говорим про саму картинку.
ab_phase_plan() {
    printf '%s\n' \
        "10:$(case_action "$1")" \
        "30:пан, картинка как открылась" \
        "8:ОТДАЛИТЬ до упора" \
        "15:пан по отдалённой картинке" \
        "12:ПРИБЛИЗИТЬ до упора" \
        "25:пан по приближённой картинке"
}

# Фазы с посчитанными секундами, по строке «секунды<TAB>подпись».
# Остаток от деления уходит в последнюю фазу, поэтому сумма секунд точно равна [$2].
ab_phases() {
    local case_id="$1" total="$2" plan idx=0 spent=0 count share label secs
    plan="$(ab_phase_plan "$case_id")"
    count="$(printf '%s\n' "$plan" | grep -c .)"
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        idx=$((idx + 1))
        share="${line%%:*}"
        label="${line#*:}"
        if [ "$idx" -eq "$count" ]; then
            secs=$((total - spent))
        else
            secs=$((total * share / 100))
        fi
        spent=$((spent + secs))
        printf '%s\t%s\n' "$secs" "$label"
    done <<PLAN
$plan
PLAN
}

# ── Сценарии жеста для run ───────────────────────────────────────────────────
# Длительность прогона в секундах. У ZOOM_OUT меняет ожидание после жеста, чтобы можно
# было дождаться загрузки. Остальные сценарии сохраняют фиксированный план.
# Оба варианта сравнения снимаются с одной длительностью.
RUN_DURATION_S="${RUN_DURATION_S:-60}"

# Сценарии, которые кейс поддерживает. Первый в списке берётся по умолчанию.
# У tiled-clg конфигурация одна, а жеста три, и различает их только человек.
case_scenarios() {
    case "$1" in
        naive-small) echo "D0" ;;
        naive-*)     echo "OOM" ;;
        tiled-c-large) echo "ZOOM_OUT" ;;
        tiled-cl-large) echo "E ZOOM_OUT" ;;
        tiled-clg-*) echo "A B C" ;;
        tiled-cl-*)  echo "E" ;;
        tiled-lg-*)  echo "F" ;;
        tiled-cg-*)  echo "G" ;;
        tiled-off-*) echo "OFF" ;;
    esac
}

case_default_scenario() { case_scenarios "$1" | awk '{print $1}'; }

case_has_scenario() {
    local s
    for s in $(case_scenarios "$1"); do [ "$s" = "$2" ] && return 0; done
    return 1
}

run_scenario_label() {
    case "$1" in
        A)   echo "A, холодный старт и паническое панорамирование" ;;
        B)   echo "B, прогон по уровням детализации" ;;
        C)   echo "C, обычное разглядывание" ;;
        E)   echo "E, graphicsLayer выключен" ;;
        F)   echo "F, culling выключен" ;;
        G)   echo "G, LOD выключен" ;;
        ZOOM_OUT) echo "отдаление до упора и ожидание полной загрузки" ;;
        D0)  echo "D-0, наивный режим на SMALL" ;;
        OOM) echo "OOM, ждём отказ" ;;
        OFF) echo "все тумблеры выключены, протокольного сценария нет" ;;
    esac
}

# План фаз прогона, по строке «секунды:подпись». Сумма секунд равна RUN_DURATION_S.
# Пустой вывод означает, что вести по таймеру нечего, потому что прогон идёт до отказа.
#
# Подписи содержат только действия, без объяснений, потому что человек читает их под
# таймером и лишнее слово стоит секунд. Объяснения живут здесь.
#
# Первая фаза везде это переключение ассета и холодный декод, потому что тумблеры
# выставляются до записи и внутри записи остаётся одно действие.
#
# У D0 зум стоит первым, потому что на самом маленьком ассете вписанная картинка занимает
# экран целиком, ход пана равен нулю, и свайпать до приближения физически некуда.
#
# У F движение только в одну сторону. Без culling очередь декодера перегружается в первые
# секунды, и дальше важна пройденная площадь, а не возврат к началу.
#
# У ZOOM_OUT после отдаления нет новых жестов, чтобы декодеры успели закончить загрузку.
# Запись заканчивается по таймеру, чтобы оба варианта имели одинаковую длительность.
run_phase_plan() {
    local case_id="$1" scenario="$2" action
    action="$(case_action "$case_id")"
    case "$scenario" in
        ZOOM_OUT) printf '%s\n' \
            "15:$action, дождись полной загрузки, дальше не трогай" \
            "10:ОТДАЛИТЬ до упора, без пана, затем отпусти жест" \
            "$((10#$RUN_DURATION_S - 25)):дождись полной загрузки, больше не трогай картинку" ;;
        A) printf '%s\n' \
            "5:$action, дальше не трогай" \
            "25:быстрые короткие свайпы, направление менять раз в секунду" \
            "30:медленные длинные свайпы от края до края, ~3с на свайп" ;;
        B) printf '%s\n' \
            "7:$action, дальше не трогай" \
            "15:ПРИБЛИЗИТЬ до упора и обратно, 3 захода по ~4с" \
            "15:ОТДАЛИТЬ до упора и обратно, 3 захода по ~4с" \
            "23:мелкие приближения и отдаления около середины, ~10 раз" ;;
        C) printf '%s\n' \
            "7:$action, дальше не трогай" \
            "53:пан, приближение, пан по приближённой, отдаление, и так по кругу" ;;
        E) printf '%s\n' \
            "5:$action, дальше не трогай" \
            "25:быстрые короткие свайпы, направление менять раз в секунду" \
            "15:медленные длинные свайпы от края до края" \
            "15:ПРИБЛИЗИТЬ примерно втрое и обратно, 3 раза" ;;
        F) printf '%s\n' \
            "5:$action, дальше не трогай" \
            "55:свайпы в одну сторону, к краю изображения, ~2-3с на свайп" ;;
        G) printf '%s\n' \
            "5:$action, дальше не трогай" \
            "55:без пана: ОТДАЛИТЬ ~5с, пауза, ПРИБЛИЗИТЬ ~5с, пауза" ;;
        D0) printf '%s\n' \
            "3:$action" \
            "10:ПРИБЛИЗИТЬ щипком до упора" \
            "22:быстрые свайпы по приближённой картинке" \
            "25:щипки туда-обратно, несколько циклов" ;;
        OFF) printf '%s\n' \
            "5:$action, дальше не трогай" \
            "25:пан по картинке как открылась" \
            "10:ОТДАЛИТЬ до упора" \
            "20:ПРИБЛИЗИТЬ до упора, потом пан" ;;
        *) ;;
    esac
}

# План прогона в том же виде, что отдаёт ab_phases, то есть «секунды<TAB>подпись».
run_phases() {
    local line
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        printf '%s\t%s\n' "${line%%:*}" "${line#*:}"
    done <<PLAN
$(run_phase_plan "$1" "$2")
PLAN
}

# Ведёт человека по фазам плана [$1] обратным отсчётом.
#
# Строку дочищаем через \033[K, а не выравниванием по ширине, потому что printf считает
# ширину в байтах, а подписи кириллические.
run_countdown() {
    local plan="$1" phase=0 total left secs label
    total="$(printf '%s\n' "$plan" | grep -c .)"
    while IFS="$(printf '\t')" read -r secs label; do
        [ -n "$secs" ] || continue
        phase=$((phase + 1))
        left="$secs"
        while [ "$left" -gt 0 ]; do
            printf "\r  [%d/%d] %s, ещё %3dс\033[K" "$phase" "$total" "$label" "$left"
            sleep 1
            left=$((left - 1))
        done
        [ "$phase" -lt "$total" ] && ab_bell 1
    done <<PHASES
$plan
PHASES
    printf "\r  план отработан\033[K\n"
    ab_bell 2
}

# Гарантирован ли отказ у кейса. Наивный режим на среднем и большом ассете отказывает
# всегда, потому что платформа такой битмап не рисует.
#
# Для тайлового режима отказ не ожидается ни в какой конфигурации. Память при выключенном
# culling растёт, но процесс это переживает.
case_failure() {
    case "$1" in
        naive-medium|naive-large) echo "certain" ;;
        *) echo "no" ;;
    esac
}

case_trigger() {
    case "$1" in
        naive-small) echo "mode" ;;
        *) echo "asset" ;;
    esac
}

case_mode() {
    case "$1" in
        naive-*) echo "Naive" ;;
        tiled-*) echo "Tiled" ;;
    esac
}

case_toggles() {
    case "$1" in
        naive-*)     echo "-" ;;
        tiled-off-*) echo "все выключены" ;;
        tiled-c-*)   echo "culling" ;;
        tiled-cl-*)  echo "culling + LOD" ;;
        tiled-clg-*) echo "culling + LOD + graphicsLayer" ;;
        tiled-lg-*)  echo "LOD + graphicsLayer" ;;
        tiled-cg-*)  echo "culling + graphicsLayer" ;;
    esac
}

case_asset() {
    case "$1" in
        *-small)  echo "SMALL" ;;
        *-medium) echo "MEDIUM" ;;
        *-large)  echo "LARGE" ;;
    esac
}

case_note() {
    case "$1" in
        naive-small)  echo "стартовая конфигурация приложения, единственный наивный режим с установившимся состоянием" ;;
        naive-medium) echo "ожидается отказ отрисовки и откат в Tiled, это штатное поведение" ;;
        naive-large)  echo "ожидается отказ на декоде и откат в Tiled" ;;
        # Самый тяжёлый прогон комплекта. Кеш ограничен бюджетом, поэтому RAM должна
        # выйти на полку, а часть картинки остаться непрогруженной.
        tiled-off-large) echo "culling выкл, нужна вся сетка тайлов. Самый тяжёлый прогон комплекта по памяти" ;;
        tiled-off-medium) echo "тайловый режим без единой оптимизации, ждём роста RAM и провала hit-rate" ;;
        # Контрасты F и G, где погашено одно оружие, а остальные оставлены.
        tiled-lg-large) echo "контраст F, culling выкл, нужны все тайлы уровня. Ждём роста RAM и провала hit-rate" ;;
        tiled-cg-large) echo "контраст G, LOD выкл, sampleSize равен 1 даже на отдалении. Ждём роста decode и wait" ;;
        tiled-c-large) echo "пара к tiled-cl-large для проверки LOD, graphicsLayer выкл, sampleSize равен 1 весь прогон" ;;
        *) echo "" ;;
    esac
}

is_known_case() {
    for c in $CASES; do [ "$c" = "$1" ] && return 0; done
    return 1
}

# ── Вспомогательное ──────────────────────────────────────────────────────────
# Печатает ошибку и обрывает работу.
die() { echo "Ошибка: $*" >&2; exit 1; }

# Печатает, какое дерево собирается и откуда оно выбрано. Нужно потому, что собираемое
# дерево и дерево со скриптом это не всегда одно и то же, а перепутать сборки в A/B проще
# всего именно здесь.
announce_root() {
    echo "Дерево:  $PROJECT_ROOT"
    echo "         $BRANCH @ $SHA, выбрано по: $PROJECT_ROOT_SOURCE"
    [ "$PROJECT_ROOT" = "$SCRIPT_ROOT" ] && return 0
    echo "         скрипт и комплекты остаются в $SCRIPT_ROOT"
}

# Печатает строку [$1], добитую пробелами до ширины [$2]. Своя, потому что printf считает
# байты, а не символы, и кириллица в его колонках разъезжается.
pad() {
    local s="$1" w="$2" n=${#1}
    printf "%s" "$s"
    while [ "$n" -lt "$w" ]; do printf " "; n=$((n + 1)); done
}

# Требует ровно одно подключённое устройство. Иначе обрывает работу.
require_device() {
    local count
    count="$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
    [ "$count" = "0" ] && die "устройство не подключено (adb devices пуст). Проверь кабель и отладку по USB."
    [ "$count" != "1" ] && die "подключено несколько устройств ($count). Оставь одно или задай ANDROID_SERIAL."
    return 0
}

mem_available_mb() {
    "$ADB" shell cat /proc/meminfo 2>/dev/null | awk '/^MemAvailable:/ {print int($2/1024); exit}'
}

device_uptime_s() {
    "$ADB" shell cat /proc/uptime 2>/dev/null | awk '{print int($1); exit}'
}

# ── Предполётная проверка устройства ─────────────────────────────────────────
# Печатает состояние устройства и считает замечания. Решение оставляет человеку, потому
# что часть свойств читается с прошивки неоднозначно, и ложный отказ стоил бы дороже
# предупреждения.
#
# Питание по USB замечанием не считается, потому что провод нужен самому adb. Ругаемся
# только на зарядку от сети.
preflight_device() {
    local warn=0 v v2 v3
    echo "Предполётная проверка:"

    v="$("$ADB" shell dumpsys battery 2>/dev/null | awk -F': *' '/^ *level:/ {print $2; exit}' | tr -d '\r')"
    if [ -n "$v" ] && [ "$v" -lt 60 ] 2>/dev/null; then
        echo "  заряд               $v %   ЗАМЕЧАНИЕ: ниже 60 %, governor уйдёт в power-save"
        warn=$((warn + 1))
    else
        echo "  заряд               ${v:-?} %"
    fi

    v="$("$ADB" shell dumpsys battery 2>/dev/null | awk -F': *' '/AC powered/ {print $2; exit}' | tr -d '\r')"
    if [ "$v" = "true" ]; then
        echo "  зарядка от сети     да      ЗАМЕЧАНИЕ: отключи, иначе троттлинг придёт раньше"
        warn=$((warn + 1))
    fi

    # Максимум по живым зонам. Наивный разбор dumpsys здесь врёт по трём причинам.
    #
    # 1. Выше живых зон печатается секция «Cached temperatures», а это снимок с прошлого
    #    раза, где телефон может быть ещё горячим. Берём только «Current temperatures
    #    from HAL», она заканчивается первой строкой без отступа.
    # 2. Зона mName=soc с mType=8 на этом устройстве всегда отдаёт 100.0, потому что это
    #    процент заряда, а не градусы.
    # 3. Незаполненные датчики отдают ровно 0.0, их тоже пропускаем.
    v="$("$ADB" shell dumpsys thermalservice 2>/dev/null |
        awk '/Current temperatures from HAL/ {f = 1; next}
             f && /^[^[:space:]]/ {exit}
             f && /mValue=/ {
                 if ($0 ~ /mName=soc/ || $0 ~ /mType=8/) next
                 split($0, a, "mValue="); split(a[2], b, ",")
                 if (b[1] + 0 > 0 && b[1] + 0 > m) m = b[1] + 0
             }
             END {if (m) printf "%.1f", m}')"
    if [ -n "$v" ] && [ "${v%%.*}" -ge 40 ] 2>/dev/null; then
        echo "  самая горячая зона  $v °C  ЗАМЕЧАНИЕ: выше 40 °C, дай остыть с выключенным экраном"
        warn=$((warn + 1))
    else
        echo "  самая горячая зона  ${v:-?} °C"
    fi

    v="$("$ADB" shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null | tr -d '\r')"
    if [ "$v" = "powersave" ]; then
        echo "  governor            $v   ЗАМЕЧАНИЕ: устройство остыло не полностью"
        warn=$((warn + 1))
    else
        echo "  governor            ${v:-?}"
    fi

    v="$("$ADB" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')"
    if [ "$v" != "1" ]; then
        echo "  режим полёта        выкл    ЗАМЕЧАНИЕ: сеть даёт фоновые пробуждения"
        warn=$((warn + 1))
    else
        echo "  режим полёта        вкл"
    fi

    v="$("$ADB" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')"
    if [ "$v" = "0" ] || [ -z "$v" ]; then
        echo "  stay awake          выкл    ЗАМЕЧАНИЕ: экран уснёт посреди записи"
        warn=$((warn + 1))
    fi

    v="$("$ADB" shell settings get global window_animation_scale 2>/dev/null | tr -d '\r')"
    v2="$("$ADB" shell settings get global transition_animation_scale 2>/dev/null | tr -d '\r')"
    v3="$("$ADB" shell settings get global animator_duration_scale 2>/dev/null | tr -d '\r')"
    if [ "$v$v2$v3" != "1.01.01.0" ] && [ "$v$v2$v3" != "nullnullnull" ]; then
        echo "  animation scale     $v/$v2/$v3  ЗАМЕЧАНИЕ: нужны 1x, иначе анимации не те"
        warn=$((warn + 1))
    fi

    # Частота экрана. Спрашиваем у дисплея, какая частота работает сейчас, а не что
    # записано в настройке, потому что настройка задаёт только потолок и хранится
    # на прошивках по-разному. Значения приходят вида 60.000004, поэтому сравниваем
    # по целой части.
    #
    # На погашенном экране дисплей отдаёт не рабочую частоту, а свой максимальный режим.
    # Читать её в этот момент бессмысленно, а ругаться тем более, потому что пауза между
    # прогонами как раз проходит с выключенным экраном.
    local screen_on
    screen_on="$("$ADB" shell dumpsys display 2>/dev/null |
        grep -oE 'committedState [A-Z]+' | head -1 | awk '{print $2}')"
    v="$("$ADB" shell dumpsys display 2>/dev/null |
        awk -F'=' '/mActiveRenderFrameRate=/ {print int($2); exit}')"
    [ -n "$v" ] || v="$("$ADB" shell settings get system peak_refresh_rate 2>/dev/null |
        awk '{print int($1)}')"
    [ "$screen_on" = "ON" ] || v="screen-off"
    case "$v" in
        60) echo "  частота экрана      60 Гц" ;;
        screen-off) echo "  частота экрана      экран погашен, будет прочитана при включении" ;;
        0|"") echo "  частота экрана      не читается, проверь глазами: нужны 60 Гц" ;;
        *)  echo "  частота экрана      $v Гц  ЗАМЕЧАНИЕ: нужны 60, иначе бюджет кадра не 16.6 мс"
            echo "                      вернуть руками: Настройки - Экран - Частота обновления - 60 Гц"
            warn=$((warn + 1)) ;;
    esac

    v="$(mem_available_mb)"
    echo "  MemAvailable        ${v:-?} МБ"
    v="$(device_uptime_s)"
    echo "  аптайм              $((${v:-0} / 60)) мин"

    # Фоновые приложения вытесняют наше из памяти и дают чужие GC-паузы.
    "$ADB" shell am kill-all >/dev/null 2>&1
    echo "  фоновые приложения  сняты (am kill-all)"

    if [ "$warn" -gt 0 ]; then
        echo
        echo "Замечаний: $warn. Прогон на таком устройстве сравнивать будет не с чем."
        printf "Всё равно снимать? Enter - да, Ctrl-C - выйти: "
        read -r _
    fi
    echo
}

# ── Запись экрана ────────────────────────────────────────────────────────────
# Включается переменной RUN_RECORD=on, по умолчанию выключена.
#
# Видео кодирует сам телефон, тем же чипом, который в это время собирает кадры, поэтому
# запись теоретически стоит кадров. Отсюда правило не «без видео», а «одинаково»: в одной
# таблице все прогоны либо с видео, либо без.
#
# Пишем через scrcpy.
#   adb shell 'screenrecord --time-limit 2 /sdcard/p.mp4; echo rc=$?'
#
# Флаг --no-playback гасит окно и декодирование на компьютере, оставляя только кодирование
# на телефоне и поток по USB. Пишем сразу на компьютер.
RUN_RECORD_BITRATE="${RUN_RECORD_BITRATE:-8M}"
SCREENREC_PID=""
SCREENREC_PATH=""
SCREENREC_LOG=""

# Есть ли на машине scrcpy.
screenrec_available() { command -v scrcpy >/dev/null 2>&1; }

# Гасит фонового потомка [$1] мягким сигналом [$2], ждёт [$3] секунд и добивает KILL.
#
# Порядок именно такой, потому что голый `wait` на живом потомке блокирует навсегда, и
# Ctrl-C из такого ожидания не выводит: сигнал встаёт в очередь, а bash ждёт возврата
# из wait. После добивания ждать уже нечего.
stop_child() {
    local pid="$1" sig="${2:-TERM}" left="${3:-5}"
    [ -n "$pid" ] || return 0
    if ! kill -0 "$pid" 2>/dev/null; then
        wait "$pid" 2>/dev/null
        return 0
    fi
    kill -"$sig" "$pid" 2>/dev/null
    while [ "$left" -gt 0 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 1
        left=$((left - 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill -KILL "$pid" 2>/dev/null
        sleep 1
    fi
    wait "$pid" 2>/dev/null
    return 0
}

# Запускает запись экрана прогона [$1] с пределом [$2] секунд.
#
# Предел отдаём самому scrcpy через --time-limit, потому что по своему пределу он
# закрывает контейнер сам. Остановка сигналом почти всегда оставляет mp4 без moov-атома,
# то есть файл, который не открывает ни один плеер.
screenrec_start() {
    local stem="$1" limit="$2"
    SCREENREC_PID=""
    SCREENREC_PATH=""
    if ! screenrec_available; then
        echo "  ВНИМАНИЕ: scrcpy не найден, пишу прогон без видео (brew install scrcpy)."
        return 0
    fi
    mkdir -p "$REC_DIR"
    SCREENREC_PATH="$REC_DIR/${stem}.mp4"
    SCREENREC_LOG="$(mktemp -t panzoom-scrcpy)"
    # scrcpy ищет adb сам, своим способом, а в PATH его может не быть. Найденный путь
    # отдаём ему двумя способами сразу, переменной ADB и каталогом в PATH.
    ADB="$ADB" PATH="$(dirname "$ADB"):$PATH" \
        scrcpy --no-audio --no-playback --video-bit-rate "$RUN_RECORD_BITRATE" \
        --time-limit="$limit" --record "$SCREENREC_PATH" >"$SCREENREC_LOG" 2>&1 &
    SCREENREC_PID=$!
    # Ждём, пока scrcpy зальёт на устройство свой сервер.
    local waited=0
    while [ "$waited" -lt 10 ]; do
        grep -q "Recording started\|New display\|INFO: Device" "$SCREENREC_LOG" 2>/dev/null && break
        kill -0 "$SCREENREC_PID" 2>/dev/null || break
        sleep 1
        waited=$((waited + 1))
    done
    if ! kill -0 "$SCREENREC_PID" 2>/dev/null; then
        echo "  ВНИМАНИЕ: scrcpy не запустился, пишу прогон без видео."
        sed 's/^/    /' "$SCREENREC_LOG" | tail -5
        SCREENREC_PID=""
        SCREENREC_PATH=""
        return 0
    fi
}

# Читается ли файл [$1] как видео.
screenrec_valid() {
    local f="$1"
    [ -s "$f" ] || return 1
    if command -v ffprobe >/dev/null 2>&1; then
        ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" >/dev/null 2>&1
    else
        LC_ALL=C grep -a -q moov "$f"
    fi
}

# Останавливает запись. Молчит, если запись не стартовала.
screenrec_stop() {
    [ -n "$SCREENREC_PID" ] || return 0
    # Сначала просто ждём, пока scrcpy досчитает свой --time-limit и закроет контейнер
    # сам. Это единственный путь получить читаемый файл, поэтому ему дают фору.
    local left="${SCREENREC_GRACE_S:-25}"
    while [ "$left" -gt 0 ] && kill -0 "$SCREENREC_PID" 2>/dev/null; do
        sleep 1
        left=$((left - 1))
    done
    # Если не ушёл сам, снимаем принудительно. Файл после этого скорее всего битый, зато
    # прогон не висит из-за записи экрана.
    stop_child "$SCREENREC_PID" TERM 10
    if screenrec_valid "$SCREENREC_PATH"; then
        echo "Видео: $SCREENREC_PATH"
    elif [ -s "$SCREENREC_PATH" ]; then
        echo "ВНИМАНИЕ: видео оборвано и не откроется, запись не закрыла контейнер:"
        echo "  $SCREENREC_PATH"
    else
        echo "ВНИМАНИЕ: видео не записалось. Последние строки scrcpy:"
        [ -f "$SCREENREC_LOG" ] && sed 's/^/    /' "$SCREENREC_LOG" | tail -5
    fi
    [ -f "$SCREENREC_LOG" ] && rm -f "$SCREENREC_LOG"
    SCREENREC_PID=""
    SCREENREC_PATH=""
    SCREENREC_LOG=""
}

# Перезагружает устройство и ждёт, пока оно устаканится.
#
# Ждём свойство sys.boot_completed, а не сам adb, потому что wait-for-device возвращается
# сразу после подъёма adbd, когда система ещё грузится.
reboot_device() {
    echo "Перезагружаю устройство..."
    "$ADB" reboot || die "adb reboot не отработал"
    "$ADB" wait-for-device
    printf "Жду окончания загрузки"
    while [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        printf "."
        sleep 2
    done
    echo
    local settle_left="$AB_SETTLE_S"
    while [ "$settle_left" -gt 0 ]; do
        printf "\rДаю системе устаканиться, ещё %3dс\033[K" "$settle_left"
        sleep 1
        settle_left=$((settle_left - 1))
    done
    printf "\rСистема устаканилась за ${AB_SETTLE_S}с\033[K\n"
    ab_bell 1
}

# Сколько секунд покрывает лог [$1]. Считается как разница между первой и последней
# метками времени, поэтому ни год, ни часовой пояс значения не имеют.
log_span_s() {
    python3 - "$1" <<'PYEOF'
import re, sys
from datetime import datetime, timedelta

RE_TS = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})")

first = last = None
try:
    with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
        for line in f:
            m = RE_TS.match(line)
            if not m:
                continue
            mo, d, h, mi, sec, ms = (int(x) for x in m.groups())
            t = datetime(2000, mo, d, h, mi, sec, ms * 1000)
            if first is None:
                first = t
            last = t
except OSError:
    pass

if first is None or last is None:
    print("0")
else:
    delta = last - first
    if delta < timedelta(0):
        delta += timedelta(days=1)
    print(int(delta.total_seconds()))
PYEOF
}

# Смещение первого отказа от первой строки лога [$1], в секундах. Знак вопроса, если
# отказа нет.
#
# В метках threadtime года нет, поэтому разницу считаем в пределах суток.
crash_offset() {
    python3 - "$1" <<'PYEOF'
import re, sys
from datetime import datetime, timedelta

RE_TS = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})")
RE_CRASH = re.compile(r"FATAL EXCEPTION|OutOfMemoryError|Наивная (загрузка|отрисовка)")

def ts(line):
    m = RE_TS.match(line)
    if not m:
        return None
    mo, d, h, mi, sec, ms = (int(x) for x in m.groups())
    return datetime(2000, mo, d, h, mi, sec, ms * 1000)

first = crash = None
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        t = ts(line)
        if t is None:
            continue
        if first is None:
            first = t
        if crash is None and RE_CRASH.search(line):
            crash = t
            break

if first is None or crash is None:
    print("?")
else:
    delta = crash - first
    if delta < timedelta(0):
        delta += timedelta(days=1)
    print(f"{delta.total_seconds():.1f}")
PYEOF
}

# Проверяет пригодность снятого прогона, то есть ту ли конфигурацию сняли и есть ли что
# анализировать. Код возврата приходит от baseline_verify.py.
verify_run() {
    local case_id="$1" log="$2" gfx="${3:-}" route="${4:-no}" expect="${5:-}"
    # Дамп кадров лежит рядом под тем же именем, из него берутся janky и перцентили.
    # A/B складывает его иначе, поэтому путь можно передать третьим аргументом.
    [ -n "$gfx" ] || gfx="$GFX_DIR/$(basename "$log" .logcat).txt"
    [ -f "$gfx" ] || gfx=""
    python3 "$SCRIPT_ROOT/scripts/baseline_verify.py" "$log" \
        ${gfx:+--gfxinfo "$gfx"} \
        --hot-logs "$HOT_LOGS" \
        --case "$case_id" \
        --asset "$(case_asset "$case_id")" \
        --mode "$(case_mode "$case_id")" \
        --graphics-layer "$(case_gl "$case_id")" \
        --culling "$(case_culling "$case_id")" \
        --kind "$(case_kind "$case_id")" \
        --trigger "$(case_trigger "$case_id")" \
        --failure "$(case_failure "$case_id")" \
        ${expect:+--expect-seconds "$expect"} \
        ${route:+$([ "$route" = "yes" ] && echo --route)}
}

# Путь к самому свежему логу кейса [$1] в комплекте.
latest_log_for_case() {
    ls -t "$RAW_DIR/$1"_*.logcat 2>/dev/null | head -1
}

# Разбивка прогонов кейса [$1] по сценариям, строкой вида "A:3 B:3 C:0".
#
# Нужна потому, что у одного кейса бывает несколько сценариев, и общее число прогонов
# скрывает, что какой-то из них не снят вовсе.
#
# Прогоны, снятые до того как сценарий попал в имя файла, засчитываются первому сценарию
# кейса, потому что других у него тогда и не было.
runs_by_scenario() {
    local case_id="$1" sc n out="" counted=0 total legacy idx=0
    total="$(runs_count_for_case "$case_id")"
    for sc in $(case_scenarios "$case_id"); do
        counted=$((counted + $(ls "$RAW_DIR/${case_id}_${sc}"_*.logcat 2>/dev/null | wc -l | tr -d ' ')))
    done
    legacy=$((total - counted))
    for sc in $(case_scenarios "$case_id"); do
        idx=$((idx + 1))
        n="$(ls "$RAW_DIR/${case_id}_${sc}"_*.logcat 2>/dev/null | wc -l | tr -d ' ')"
        [ "$idx" -eq 1 ] && n=$((n + legacy))
        out="$out${out:+ }$sc:$n"
    done
    printf '%s' "$out"
}

# Сколько всего прогонов кейса [$1] снято в этом комплекте.
runs_count_for_case() {
    ls "$RAW_DIR/$1"_*.logcat 2>/dev/null | wc -l | tr -d ' '
}

# ── README ───────────────────────────────────────────────────────────────────
# Пересобирает README комплекта по снятым логам и свойствам подключённого устройства.
write_readme() {
    mkdir -p "$BASE_DIR"
    {
        echo "# $(run_title), $SHA"
        echo
        echo "- Устройство: $(prop ro.product.brand) $(prop ro.product.model) ($(prop ro.product.device))"
        echo "- Android: $(prop ro.build.version.release) (API $(prop ro.build.version.sdk)), сборка $(prop ro.build.display.id)"
        echo "- buildType: $BUILD_TYPE, коммит $(git -C "$PROJECT_ROOT" rev-parse HEAD)"
        echo "- Горячие логи (BuildConfig.HOT_LOGS): $HOT_LOGS"
        echo "- R8: $R8"
        echo "- Бюджеты: TileCache $CACHE_MB МБ, пул битмапов $POOL_MB МБ"
        echo
        echo "Снято обвязкой \`scripts/baseline.sh\`."
        echo
        echo "## С чем сравнивать"
        echo
        echo "Сравнимы между собой только комплекты, у которых совпадают коммит, флаги и устройство"
        echo "(см. \`run.env\`). Что с чем имеет смысл сопоставлять:"
        echo
        echo "- цена наблюдения, то есть этот комплект против такого же с \`hot-off\`;"
        echo "- влияние правки, то есть комплекты до и после неё на одном устройстве."
        echo
        echo "Комплекты, снятые с другими флагами сборки, сравнивать нельзя."
        echo
        echo "## Прогоны"
        echo
        echo "Прогоны с типом \`failure\` меряют время до отказа и пик RAM, а не кадры,"
        echo "потому что установившегося состояния в этих конфигурациях не существует."
        echo
        echo "| Кейс | Режим | Тумблеры | Ассет | Тип | Лог | Примечание |"
        echo "|------|-------|----------|-------|-----|-----|------------|"
        for c in $CASES; do
            local log status
            log="$(latest_log_for_case "$c")"
            local n
            n="$(runs_count_for_case "$c")"
            if [ -n "$log" ]; then
                status="$n шт ($(runs_by_scenario "$c")), свежий \`$(basename "$log")\`"
            else
                status="—"
            fi
            echo "| $c | $(case_mode "$c") | $(case_toggles "$c") | $(case_asset "$c") | $(case_kind "$c") | $status | $(case_note "$c") |"
        done
    } > "$README"
}

# ── Команды ──────────────────────────────────────────────────────────────────
# Проверяет окружение и ничего не меняет.
cmd_doctor() {
    announce_root
    echo "логи:     $LOGS_ROOT/logs"
    echo "adb:      $ADB"
    echo "python3:  $(command -v python3 || echo 'НЕ НАЙДЕН')"
    echo "конвертер: $SCRIPT_ROOT/scripts/logcat_to_csv.py"
    [ -f "$SCRIPT_ROOT/scripts/logcat_to_csv.py" ] || echo "  ^ файла нет, CSV собрать не выйдет"
    echo
    echo "Комплект: $(run_title)"
    echo "  каталог:      $BASE_DIR"
    echo "  коммит:       $SHA"
    echo "  горячие логи: $HOT_LOGS (BuildConfig.HOT_LOGS)"
    echo "  R8:           $R8"
    echo "  бюджеты:      TileCache $CACHE_MB МБ, пул $POOL_MB МБ"
    [ "$HOT_LOGS" = "unknown" ] && echo "  ^ флаг не прочитан: прогон снимать нельзя, цифры будет не с чем сравнить"
    echo
    "$ADB" devices -l
    require_device
    echo
    echo "Устройство: $(prop ro.product.brand) $(prop ro.product.model)"
    echo "Android:    $(prop ro.build.version.release) (API $(prop ro.build.version.sdk))"
    if "$ADB" shell pm list packages 2>/dev/null | grep -q "package:$APP"; then
        echo "Приложение: установлено"
    else
        echo "Приложение: НЕ установлено - запусти ./scripts/baseline.sh build"
    fi
}

# Собирает APK и ставит его на устройство.
cmd_build() {
    announce_root
    require_device
    local jdk="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    [ -d "$jdk" ] || die "не найден JDK Android Studio: $jdk"
    local task="assembleRelease"
    [ "$BUILD_TYPE" = "debug" ] && task="assembleDebug"
    # Отклонение по горячим логам уходит в gradle тем же значением, по которому назван
    # каталог комплекта, чтобы собранное и записанное не разъехались.
    local props=""
    [ -n "${HOT_LOGS_OVERRIDE:-}" ] && props="-Ppanzoom.hotLogs=$HOT_LOGS_OVERRIDE"
    echo "Сборка $BUILD_TYPE..."
    JAVA_HOME="$jdk" "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" $props ":app:$task" || die "сборка упала"
    local apk
    apk="$(built_apk)"
    [ -n "$apk" ] || die "APK не найден в app/build/outputs/apk/$BUILD_TYPE/"
    check_apk_fresh
    echo "APK новее всех исходников, собран в $(date -r "$apk" '+%H:%M')."
    # Проверяем, что R8 действительно отработал. Без
    # mapping.txt включённый флаг ничего не значит, и замеры теряют смысл.
    if [ "$R8" = "on" ]; then
        local mapping="$PROJECT_ROOT/app/build/outputs/mapping/release/mapping.txt"
        [ -f "$mapping" ] || die "R8 включён, но $mapping не появился - шринк не отработал"
        echo "R8 отработал: mapping.txt на месте."
    fi
    echo "Установка $(basename "$apk")..."
    "$ADB" install -r "$apk" || die "установка не прошла"
    echo "Поставлено: $BRANCH @ $SHA из $PROJECT_ROOT"
    echo "Готово. Комплект: $(run_title), горячие логи $HOT_LOGS, R8 $R8."
    echo "Дальше: ./scripts/baseline.sh list"
}

# Печатает чек-лист прогонов текущего комплекта со статусами.
cmd_list() {
    announce_root
    echo "Комплект: $(run_title)"
    echo "Каталог:  $BASE_DIR"
    echo "Сборка:   $SHA, горячие логи $HOT_LOGS, R8 $R8"
    echo
    pad "КЕЙС" 18; pad "РЕЖИМ" 7; pad "ТУМБЛЕРЫ" 30; pad "АССЕТ" 8; pad "ТИП" 9; echo "СТАТУС"
    for c in $CASES; do
        local log status
        log="$(latest_log_for_case "$c")"
        local n
        n="$(runs_count_for_case "$c")"
        if [ -n "$log" ]; then
            status="$n шт ($(runs_by_scenario "$c")), свежий $(basename "$log")"
        else
            status="—"
        fi
        pad "$c" 18
        pad "$(case_mode "$c")" 7
        pad "$(case_toggles "$c")" 30
        pad "$(case_asset "$c")" 8
        pad "$(case_kind "$c")" 9
        echo "$status"
    done
    echo
    echo "Прогон: ./scripts/baseline.sh run <кейс> [сценарий]"
    echo "        у tiled-clg конфигурация одна, а сценария три: A, B, C"
    echo "        для tiled-c-large и tiled-cl-large общий сценарий ZOOM_OUT"
}

# Снимает серию из RUN_REPEAT прогонов кейса [$1] по сценарию [$2].
#
# Серия нужна потому, что один прогон ничего не доказывает, и в таблицы идёт медиана.
# Между повторами скрипт держит паузу и заново гонит предполётную проверку, чтобы
# устройство успело остыть, а если не успело, это было видно.
cmd_run() {
    announce_root
    local case_id="$1" scenario="${2:-}" repeat="${RUN_REPEAT:-1}" i verdict=0 last=0
    is_known_case "$case_id" || die "неизвестный кейс '$case_id'. Список: ./scripts/baseline.sh list"
    # Сценарий задаёт только жест, конфигурацию он не трогает. Спрашиваем его лишь там,
    # где кейс допускает несколько, как A, B и C на одних и тех же тумблерах.
    if [ -n "$scenario" ]; then
        scenario="$(printf '%s' "$scenario" | tr '[:lower:]' '[:upper:]')"
        case_has_scenario "$case_id" "$scenario" ||
            die "кейс '$case_id' не знает сценария '$scenario'. Доступно: $(case_scenarios "$case_id")"
    else
        scenario="$(case_default_scenario "$case_id")"
    fi
    if [ "$scenario" = "ZOOM_OUT" ]; then
        case "$RUN_DURATION_S" in
            ''|*[!0-9]*) die "RUN_DURATION_S должен быть числом, а не '$RUN_DURATION_S'" ;;
        esac
        [ "$RUN_DURATION_S" -ge 60 ] || die "для ZOOM_OUT нужно не меньше 60 секунд"
    fi
    case "$repeat" in
        ''|*[!0-9]*) die "RUN_REPEAT должен быть числом, а не '$repeat'" ;;
    esac
    [ "$repeat" -ge 1 ] || die "RUN_REPEAT не может быть меньше единицы"

    # RUN_REPEAT считает годные прогоны, а не попытки. Непригодный уезжает в карантин
    # и переснимается, потому что медиана по двум годным и одному обрезанному логу это
    # не медиана.
    #
    # Потолок попыток нужен, потому что причина непригодности бывает системной, например
    # село устройство или сломался буфер логов, и тогда цикл крутился бы вечно.
    local attempts=0 ok=0 cap=$((repeat * ${RUN_MAX_ATTEMPTS:-3}))
    while [ "$ok" -lt "$repeat" ]; do
        attempts=$((attempts + 1))
        # Шапку печатаем только на серии. На одиночном прогоне она была бы шумом, а на
        # серии без неё непонятно, какой прогон идёт.
        if [ "$repeat" -gt 1 ] || [ "$attempts" -gt 1 ]; then
            echo "══ Годных $ok из $repeat, попытка $attempts ══════════════════════"
        fi
        run_once "$case_id" "$scenario"
        last=$?
        if [ "$last" -eq 0 ]; then
            ok=$((ok + 1))
        else
            verdict=$last
            reject_run "$LAST_RUN_STEM"
        fi
        [ "$ok" -ge "$repeat" ] && break
        if [ "$attempts" -ge "$cap" ]; then
            echo
            echo "Потолок попыток ($cap) исчерпан, годных прогонов $ok из $repeat."
            echo "Причина, похоже, не в жесте. Посмотри rejected/ и чини её, а не пересдавай."
            return 1
        fi
        # Про пересдачу говорим до паузы, чтобы пауза не выглядела зависанием и человек
        # не прервал серию.
        if [ "$last" -ne 0 ]; then
            echo
            echo "Прогон непригоден и в счёт не пошёл. Пересниму его."
            echo "Попытка $((attempts + 1)) из $cap. Ctrl-C - прервать."
        fi
        run_cooldown
    done
    # Серия собрана целиком, значит последний непригодный прогон уже переснят.
    [ "$ok" -ge "$repeat" ] && verdict=0
    return $verdict
}

# Убирает непригодный прогон [$1] в каталог rejected целиком, вместе с логом, дампом
# кадров, CSV и видео.
#
# Не удаляем, потому что по непригодному прогону разбираются, почему он не удался. Но и
# рядом с годными он лежать не должен, иначе чек-лист считает его за прогон, а медиану
# берут по числам, одно из которых мусор.
reject_run() {
    local stem="$1" d
    [ -n "$stem" ] || return 0
    for d in raw gfxinfo csv records; do mkdir -p "$BASE_DIR/rejected/$d"; done
    [ -f "$RAW_DIR/${stem}.logcat" ] && mv "$RAW_DIR/${stem}.logcat" "$BASE_DIR/rejected/raw/"
    [ -f "$GFX_DIR/${stem}.txt" ]    && mv "$GFX_DIR/${stem}.txt"    "$BASE_DIR/rejected/gfxinfo/"
    [ -d "$CSV_DIR/${stem}" ]        && mv "$CSV_DIR/${stem}"        "$BASE_DIR/rejected/csv/"
    [ -f "$REC_DIR/${stem}.mp4" ]    && mv "$REC_DIR/${stem}.mp4"    "$BASE_DIR/rejected/records/"
    echo "Непригодный прогон убран в rejected/, в счёт серии он не идёт."
    return 0
}

# Держит паузу между повторами, чтобы ушло тепло от предыдущего прогона и закончилась
# фоновая работа, которую разбудил force-stop.
#
# Экран не гасим сами, потому что при включённом stay awake система тут же будит его
# обратно.
run_cooldown() {
    local left="${RUN_COOLDOWN_S:-60}"
    "$ADB" shell am force-stop "$APP" >/dev/null 2>&1
    echo
    echo "Пауза перед следующим прогоном. Погаси экран, устройство остывает."
    while [ "$left" -gt 0 ]; do
        printf "\r  ещё %3dс\033[K" "$left"
        sleep 1
        left=$((left - 1))
    done
    printf "\r  пауза окончена, включай экран\033[K\n"
    ab_bell 2
    echo
}

# Имя последнего снятого прогона. Через глобальную переменную, потому что вызывающему оно
# нужно для карантина, а stdout run_once занят выводом самого прогона.
LAST_RUN_STEM=""

# Снимает один прогон кейса [$1] по сценарию [$2]. Код возврата приходит от проверки
# пригодности.
run_once() {
    local case_id="$1" scenario="$2"
    LAST_RUN_STEM=""
    require_device
    command -v python3 >/dev/null 2>&1 || die "нет python3, конвертер не отработает"
    [ "$HOT_LOGS" = "unknown" ] && die "не удалось определить HOT_LOGS для сборки $BUILD_TYPE"
    check_manifest

    mkdir -p "$RAW_DIR" "$CSV_DIR" "$GFX_DIR"

    # Предусловия проверяем до перезапуска приложения, потому что человек может уйти
    # остужать устройство, а холодный старт должен остаться холодным.
    [ "${RUN_PREFLIGHT:-on}" = "on" ] && preflight_device

    local stem out gfx_out note kind suffix
    kind="$(case_kind "$case_id")"
    # Сценарий входит в имя, потому что у прогонов одного кейса конфигурация одна, а жест
    # разный, и по файлу их иначе не различить. Кейс остаётся первым, поэтому поиск
    # по "<кейс>_*" продолжает находить всё.
    stem="${case_id}_${scenario}_$(device_stem)_$(date +%Y-%m-%d_%H%M)"
    # Суффикс нужен на случай двух прогонов одного кейса в одну минуту, чтобы переснятый
    # лог не затёр предыдущий. С ним обычно и сравнивают, почему пересняли.
    suffix=2
    while [ -f "$RAW_DIR/${stem}.logcat" ]; do
        stem="${case_id}_${scenario}_$(device_stem)_$(date +%Y-%m-%d_%H%M)-$suffix"
        suffix=$((suffix + 1))
    done
    out="$RAW_DIR/${stem}.logcat"
    gfx_out="$GFX_DIR/${stem}.txt"
    LAST_RUN_STEM="$stem"
    note="$(case_note "$case_id")"

    echo "─────────────────────────────────────────────────────────────"
    echo "  Комплект: $(run_title)"
    echo "  Сборка:   $SHA, горячие логи $HOT_LOGS, R8 $R8, бюджеты ${CACHE_MB}/${POOL_MB} МБ"
    echo "  Кейс:     $case_id"
    echo "  Сценарий: $scenario - $(run_scenario_label "$scenario")"
    echo "  Режим:    $(case_mode "$case_id")"
    echo "  Тумблеры: $(case_toggles "$case_id")"
    echo "  Ассет:    $(case_asset "$case_id")"
    case "$(case_failure "$case_id")" in
        certain)   echo "  Тип:      гарантированный отказ - снимаем время до отказа и пик RAM" ;;
        *)         echo "  Тип:      установившийся режим - снимаем FPS, jank, RAM" ;;
    esac
    [ -n "$note" ] && echo "  Внимание: $note"
    if [ "${RUN_RECORD:-off}" = "on" ]; then
        echo "  Видео:    пишется"
        echo "            важно одно, в таблице все прогоны либо с видео, либо без"
    fi
    # Трап ставим всегда, а не только под запись, потому что Ctrl-C посреди прогона иначе
    # оставляет висеть и logcat, и scrcpy. Обработчик обязан быть быстрым, поэтому внутри
    # только stop_child с жёстким добиванием.
    trap 'echo; stop_child "${log_pid:-}" TERM 3; screenrec_stop; exit 130' INT TERM
    echo "─────────────────────────────────────────────────────────────"
    # Перезапуск делаем до настройки, потому что прогон должен начинаться с чистого
    # процесса. Иначе в замер попадут прогретый пул и уже открытые декодеры прошлого кейса.
    #
    # Режим и тумблеры после перезапуска могут восстановиться, а могут и нет, потому что
    # force-stop сносит сохранённое состояние не на всех прошивках. Поэтому настройку
    # проверяют глазами в любом случае.
    echo "Перезапускаю приложение с чистого процесса..."
    "$ADB" shell am force-stop "$APP"
    "$ADB" shell am start -n "$ACTIVITY" >/dev/null

    # Всё, что надо прочитать, печатается до записи. Внутри записи человек смотрит только
    # на строку отсчёта, потому что читать и одновременно вести жест по секундам нельзя,
    # а пропущенная фаза стоит целого прогона.
    local plan
    plan="$(run_phases "$case_id" "$scenario")"

    echo
    if [ -n "$plan" ]; then
        echo "Что делать под запись, ${RUN_DURATION_S}с по фазам:"
        local n=0 p_secs p_label
        while IFS="$(printf '\t')" read -r p_secs p_label; do
            [ -n "$p_secs" ] || continue
            n=$((n + 1))
            printf "  %d) %3dс  %s\n" "$n" "$p_secs" "$p_label"
        done <<PHASES
$plan
PHASES
        if [ "$scenario" != "ZOOM_OUT" ] && [ "${RUN_HOLD:-off}" = "on" ]; then
            echo "Скрипт ведёт по фазам, после плана запись идёт до Enter."
        else
            echo "Скрипт ведёт по фазам и останавливает запись сам."
        fi
    else
        echo "Что делать под запись:"
        echo "  1) $(case_action "$case_id")"
        echo "  2) дождись отказа и жми Enter"
    fi

    # Настройка стоит последней, вплотную к подтверждению, потому что подтверждение
    # спрашивает именно про неё, и список должен остаться перед глазами.
    echo
    echo "Настройка (ДО записи, в лог не попадёт):"
    echo "  1) Ассет $SETUP_ASSET, проверь глазами."
    echo "  2) Режим $(case_setup_mode "$case_id")."
    echo "  3) Тумблеры: $(case_toggles "$case_id")."

    echo
    printf "Выставил? Enter - начинаю запись: "
    read -r _

    set_log_buffer
    "$ADB" logcat -c >/dev/null 2>&1
    "$ADB" shell dumpsys gfxinfo "$APP" reset >/dev/null 2>&1
    # Видео стартует до logcat, чтобы пара секунд на раскрутку кодера уехала в видео,
    # а не в лог, иначе первые кадры прогона окажутся дороже, чем они есть.
    #
    # У прогона с планом предел записи известен, плюс запас на то, что scrcpy стартует
    # чуть раньше логов. У прогона до отказа плана нет, поэтому берётся потолок.
    if [ "${RUN_RECORD:-off}" = "on" ]; then
        if [ -n "$plan" ]; then
            screenrec_start "$stem" $((RUN_DURATION_S + 5))
        else
            screenrec_start "$stem" 120
        fi
    fi
    "$ADB" logcat -v threadtime -T "$(date +%s.000)" > "$out" &
    local log_pid=$!
    local started_at
    started_at="$(date +%s)"

    echo
    echo "ЗАПИСЬ ИДЁТ"
    if [ -z "$plan" ]; then
        # У прогона до отказа плана нет, потому что отказ приходит когда приходит.
        printf "Enter - останавливаю запись: "
        read -r _
    else
        run_countdown "$plan"
        # Ручной хвост на случай, когда после плана надо дотянуть жест или дождаться
        # тайлов. По умолчанию выключен, иначе длительность прогонов разъедется.
        if [ "$scenario" != "ZOOM_OUT" ] && [ "${RUN_HOLD:-off}" = "on" ]; then
            printf "RUN_HOLD=on: Enter - останавливаю запись: "
            read -r _
        fi
    fi

    stop_child "$log_pid" TERM 5
    local duration=$(( $(date +%s) - started_at ))
    "$ADB" shell dumpsys gfxinfo "$APP" > "$gfx_out" 2>/dev/null
    # Видео останавливаем после дампа кадров, потому что дамп снимается с живого
    # приложения, и лишние секунды на закрытие контейнера ему ни к чему.
    screenrec_stop "$stem"

    echo
    echo "Длительность прогона: ${duration}s, строк в логе: $(wc -l < "$out" | tr -d ' ')"

    # Текст отказа показываем целиком, потому что вердикт скажет только, что отказ есть,
    # а чем именно платформа отказала, видно лишь в самих строках.
    if grep -q -E "$RE_FAILURE" "$out"; then
        echo
        echo "  Отказ в логе:"
        grep -m1 -A6 -E "$RE_FAILURE" "$out" | sed 's/^/    /'
    fi

    echo
    echo "Конвертация в CSV..."
    python3 "$SCRIPT_ROOT/scripts/logcat_to_csv.py" "$out" -o "$CSV_DIR" || die "конвертер упал"

    # Длительность проверяем только у прогонов с планом, потому что у них она
    # фиксированная, и расхождение означает прерванный отсчёт или поздний старт записи.
    verify_run "$case_id" "$out" "" no "$([ -n "$plan" ] && echo "$RUN_DURATION_S")"
    local verdict=$?

    write_readme
    echo
    echo "Лог:  $out"
    echo "CSV:  $CSV_DIR/$stem/"
    echo "gfx:  $gfx_out"
    [ -f "$CSV_DIR/$stem/summary.txt" ] && { echo; sed -n '1,25p' "$CSV_DIR/$stem/summary.txt"; }
    return $verdict
}

# Печатает, какие комплекты уже сняты и чем они друг от друга отличаются.
cmd_runs() {
    local dir found=0
    echo
    pad "КАТАЛОГ" 34; pad "КОММИТ" 10; pad "ЛОГИ" 6; pad "R8" 5; pad "БЮДЖЕТЫ" 10
    pad "ПРОГОНОВ" 10; echo "A/B"
    for dir in "$LOGS_ROOT"/logs/baseline_* "$LOGS_ROOT"/logs/run_*; do
        [ -d "$dir" ] || continue
        found=1
        local m="$dir/run.env" sha_v="?" hot_v="?" r8_v="?" budgets="?" count
        if [ -f "$m" ]; then
            sha_v="$(grep -E '^sha=' "$m" | cut -d= -f2-)"
            hot_v="$(grep -E '^hot_path_logs=' "$m" | cut -d= -f2-)"
            r8_v="$(grep -E '^r8=' "$m" | cut -d= -f2-)"
            budgets="$(grep -E '^tile_cache_budget_mb=' "$m" | cut -d= -f2-)/$(grep -E '^bitmap_pool_budget_mb=' "$m" | cut -d= -f2-)"
        fi
        count="$(ls "$dir"/raw/*.logcat 2>/dev/null | wc -l | tr -d ' ')"
        # A/B-прогоны лежат отдельно от обычных, и пару ищут именно среди них.
        local ab_count
        ab_count="$(ls "$dir"/ab/*.logcat 2>/dev/null | wc -l | tr -d ' ')"
        pad "$(basename "$dir")" 34
        pad "$sha_v" 10
        pad "$hot_v" 6
        pad "$r8_v" 5
        pad "$budgets" 10
        pad "$count" 10
        echo "$ab_count"
    done
    [ "$found" = "0" ] && echo "  комплектов ещё нет"
    echo
    echo "  Комплекты без run.env сняты до появления манифеста."
    echo "  Сравнивать между собой можно только строки с одинаковыми ЛОГИ/R8/БЮДЖЕТЫ."
    echo
}

# Снимает прогон кейса [$1] для сравнения двух сборок.
#
# От обычного прогона отличается тремя вещами. Следит за состоянием памяти устройства,
# держит фиксированную длительность и в конце сам ищет пару в комплекте другой сборки.
cmd_ab() {
    announce_root
    local case_id="$1"
    is_known_case "$case_id" || die "неизвестный кейс '$case_id'. Список: ./scripts/baseline.sh list"
    require_device
    # Свежесть APK проверяем до жеста, а не после, чтобы не потратить прогон впустую
    # на устаревшую сборку.
    check_apk_fresh
    command -v python3 >/dev/null 2>&1 || die "нет python3, сравнение не собрать"
    [ "$HOT_LOGS" = "unknown" ] && die "не удалось определить HOT_LOGS для сборки $BUILD_TYPE"
    check_manifest

    case " $AB_CASES " in
        *" $case_id "*) ;;
        *) echo "Внимание: '$case_id' вне набора A/B ($AB_CASES). Снимаю, раз просишь." ;;
    esac

    local ab_dir="$BASE_DIR/ab"
    mkdir -p "$ab_dir"

    local stem log gfx meta suffix
    stem="${case_id}_$(device_stem)_$(date +%Y-%m-%d_%H%M)"
    suffix=2
    while [ -f "$ab_dir/${stem}.logcat" ]; do
        stem="${case_id}_$(device_stem)_$(date +%Y-%m-%d_%H%M)-$suffix"
        suffix=$((suffix + 1))
    done
    log="$ab_dir/${stem}.logcat"
    gfx="$ab_dir/${stem}.gfx.txt"
    meta="$ab_dir/${stem}.meta"

    echo "─────────────────────────────────────────────────────────────"
    echo "  A/B прогон:   $case_id"
    echo "  Комплект:     $(basename "$BASE_DIR")"
    echo "  Сборка:       $SHA, горячие логи $HOT_LOGS, R8 $R8"
    echo "  Режим:        $(case_mode "$case_id"), тумблеры: $(case_toggles "$case_id")"
    echo "  Ассет:        $(case_asset "$case_id")"
    echo "  Длительность: ${AB_DURATION}с, фиксированная"
    echo "─────────────────────────────────────────────────────────────"

    local uptime
    uptime="$(device_uptime_s)"
    if [ -n "$uptime" ] && [ "$uptime" -gt "$AB_MAX_UPTIME_S" ]; then
        echo
        echo "Телефон не перезагружался $((uptime / 60)) мин. Память уже занята фоном,"
        echo "и второй сборке достанется не то состояние, что первой."
        if ab_ask_reboot; then
            reboot_device
            uptime="$(device_uptime_s)"
        else
            echo "Ок, снимаю как есть - но сравнение может выйти недействительным."
        fi
    fi

    local airplane
    airplane="$("$ADB" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')"
    [ "$airplane" = "1" ] || echo "Внимание: режим полёта выключен, фоновые синхронизации попадут в запись."

    local mem_before
    mem_before="$(mem_available_mb)"
    echo "MemAvailable перед прогоном: ${mem_before:-?} МБ"

    "$ADB" shell am force-stop "$APP"
    "$ADB" shell am start -n "$ACTIVITY" >/dev/null
    # Сценарий печатаем раньше настройки, потому что его читают заранее, а настройку
    # делают руками прямо перед подтверждением.
    echo
    echo "Сценарий записи, ${AB_DURATION}с. Скрипт ведёт по фазам, следи за строкой отсчёта."
    local n=0
    while IFS="$(printf '\t')" read -r secs label; do
        n=$((n + 1))
        printf "  %d) %3dс  %s\n" "$n" "$secs" "$label"
    done <<PHASES
$(ab_phases "$case_id" "$AB_DURATION")
PHASES
    echo
    echo "Настройка (до записи, в лог не попадёт):"
    echo "  1) Ассет $SETUP_ASSET, проверь глазами."
    echo "  2) Режим: $(case_setup_mode "$case_id")."
    echo "  3) Тумблеры: $(case_toggles "$case_id")."
    printf "Выставил? Enter - начинаю запись: "
    read -r _

    # Запись копится в кольцевом буфере устройства, а выгружается одним куском в конце.
    # Так связь с adb нужна только в начале и в конце, а не всю запись, и её обрыв
    # посреди жеста прогон не рубит. Места в буфере LOG_BUFFER_SIZE на это хватает.
    set_log_buffer
    "$ADB" logcat -c >/dev/null 2>&1
    "$ADB" shell dumpsys gfxinfo "$APP" reset >/dev/null 2>&1
    local started_at
    started_at="$(date +%s)"

    echo
    echo "ЗАПИСЬ ИДЁТ. Держись фаз, маршрут обязан совпасть с другой сборкой."
    local phase=0 total_phases left
    total_phases="$(ab_phases "$case_id" "$AB_DURATION" | grep -c .)"
    while IFS="$(printf '\t')" read -r secs label; do
        phase=$((phase + 1))
        left="$secs"
        while [ "$left" -gt 0 ]; do
            # \033[K вместо выравнивания шириной: printf считает её в байтах, а подписи
            # кириллические, и от длинной фазы оставались хвосты на короткой.
            printf "\r  [%d/%d] %s, ещё %3dс\033[K" "$phase" "$total_phases" "$label" "$left"
            sleep 1
            left=$((left - 1))
        done
        # Последнюю фазу не отбиваем: за ней сразу идёт двойной сигнал конца записи.
        [ "$phase" -lt "$total_phases" ] && ab_bell 1
    done <<PHASES
$(ab_phases "$case_id" "$AB_DURATION")
PHASES
    printf "\r  запись окончена\033[K\n"
    ab_bell 2

    require_device
    # Дамп кадров идёт первым, потому что снимается с живого приложения.
    "$ADB" shell dumpsys gfxinfo "$APP" > "$gfx" 2>/dev/null
    # Гасим приложение до выгрузки, чтобы очередь декода не дописала в буфер то, чего
    # в отсчёте уже не было.
    "$ADB" shell am force-stop "$APP"
    "$ADB" logcat -d -v threadtime > "$log" 2>/dev/null ||
        die "выгрузить лог не удалось. Проверь связь: $ADB devices"

    # Буфер кольцевой, поэтому при нехватке места начало записи затирается. Видно это
    # по тому, что лог покрывает меньше секунд, чем шёл отсчёт.
    local span
    span="$(log_span_s "$log")"
    if [ "$span" -lt "$AB_DURATION" ]; then
        echo "  В логе ${span}с из ${AB_DURATION}с: буфер $LOG_BUFFER_SIZE переполнился."
        echo "  Подними LOG_BUFFER_SIZE и пересними."
    fi

    local mem_after duration
    mem_after="$(mem_available_mb)"
    duration=$(( $(date +%s) - started_at ))

    # Сайдкар рядом с логом. В нём то, чего в логе нет, то есть состояние памяти до и
    # после, флаги сборки и аптайм. По ним сравнивалка и решает, годна ли пара.
    cat > "$meta" <<META
sha=$SHA
case=$case_id
run_dir=$(basename "$BASE_DIR")
build_type=$BUILD_TYPE
dirty=$DIRTY
hot_path_logs=$HOT_LOGS
r8=$R8
tile_cache_budget_mb=$CACHE_MB
bitmap_pool_budget_mb=$POOL_MB
device=$(device_stem)
duration_s=$duration
mem_before_mb=${mem_before:-}
mem_after_mb=${mem_after:-}
uptime_before_s=${uptime:-}
airplane=${airplane:-?}
META

    echo "MemAvailable после прогона: ${mem_after:-?} МБ"
    echo "Лог:  $log"
    echo "gfx:  $gfx"

    # Маршрут спрашиваем только здесь, потому что фазы обещает именно A/B-прогон.
    verify_run "$case_id" "$log" "$gfx" yes "$AB_DURATION"

    local pair same
    pair="$(ab_pair_for "$case_id")"
    if [ -z "$pair" ]; then
        echo
        echo "Прогона другой сборки для кейса '$case_id' пока нет."
        echo "Пара - это ДРУГОЙ коммит, а не пересборка этого же: комплект называется"
        echo "по sha, и второй прогон с тем же sha ложится в тот же каталог."
        echo "  git worktree add ../PanZoomDemo-base <ветка-эталон>"
        echo "  cd ../PanZoomDemo-base && ./scripts/baseline.sh build && ./scripts/baseline.sh ab $case_id"
        # Два прогона одной сборки это не A/B, зато порог значимости. Показываем его,
        # когда такой прогон уже есть.
        same="$(ab_same_build_pair_for "$case_id" "$log")"
        if [ -n "$same" ]; then
            echo
            echo "Зато есть ещё один прогон ЭТОЙ же сборки - показываю разброс замера:"
            ab_run_compare "$same" "$log" --noise
        fi
        return 0
    fi
    echo
    echo "Пара найдена: $pair"
    ab_run_compare "$pair" "$log"
}

# Запускает сравнение логов [$1] и [$2]. Пути к дампу кадров и сайдкару выводятся
# из имени лога, потому что они лежат рядом с ним.
ab_run_compare() {
    local a="$1" b="$2"
    shift 2
    # Правка, меняющая сам декод, то есть размер региона или inSampleSize, ломает признак
    # годности по медиане decodeTile. Снимается осознанно и только на время такой правки.
    local extra=""
    [ "${AB_DECODE_CHANGED:-no}" = "yes" ] && extra="--decode-changed"
    python3 "$SCRIPT_ROOT/scripts/ab_compare.py" "$a" "$b" $extra \
        --label-a "$(ab_label "$a")" \
        --label-b "$(ab_label "$b")" \
        --gfxinfo-a "${a%.logcat}.gfx.txt" \
        --gfxinfo-b "${b%.logcat}.gfx.txt" \
        --meta-a "${a%.logcat}.meta" \
        --meta-b "${b%.logcat}.meta" \
        "$@"
}

# Самый свежий A/B-прогон кейса [$1] из другого комплекта. Каталог привязан к коммиту
# и флагам сборки, поэтому другая сборка это просто другой каталог.
ab_pair_for() {
    local case_id="$1" candidate
    for candidate in $(ls -t "$LOGS_ROOT"/logs/baseline_*/ab/"$case_id"_*.logcat \
                              "$LOGS_ROOT"/logs/run_*/ab/"$case_id"_*.logcat 2>/dev/null); do
        case "$candidate" in
            "$BASE_DIR"/*) continue ;;
            *) echo "$candidate"; return 0 ;;
        esac
    done
    return 1
}

# Подпись колонки для прогона [$1]. Это комплект и время прогона, потому что в одном
# комплекте прогонов бывает несколько.
ab_label() {
    local stem
    stem="$(basename "$1" .logcat)"
    echo "$(basename "$(dirname "$(dirname "$1")")") ${stem##*_}"
}

# Ещё один A/B-прогон кейса [$1] в этом же комплекте, кроме прогона [$2].
ab_same_build_pair_for() {
    local case_id="$1" exclude="$2" candidate
    for candidate in $(ls -t "$BASE_DIR"/ab/"$case_id"_*.logcat 2>/dev/null); do
        [ "$candidate" = "$exclude" ] && continue
        echo "$candidate"
        return 0
    done
    return 1
}

# Чекауты этого репозитория, кроме того, где лежит скрипт. Пути спрашиваем у git, потому
# что worktree живёт там, где его завели, и угадать это нельзя.
other_checkouts() {
    git -C "$SCRIPT_ROOT" worktree list --porcelain 2>/dev/null |
        awk '/^worktree /{print $2}' |
        while read -r dir; do
            [ "$dir" = "$SCRIPT_ROOT" ] && continue
            [ -f "$dir/app/build.gradle.kts" ] && echo "$dir"
        done
}

# Ветка и коммит чекаута [$1] одной строкой.
checkout_title() {
    echo "$(git -C "$1" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?') @ $(git -C "$1" rev-parse --short HEAD 2>/dev/null || echo '?')"
}

# Справка по подкоманде ab-plan.
ab_plan_usage() {
    cat <<USAGE
./scripts/baseline.sh ab-plan [кейс] [--baseline-here] [--decode-changed]

Печатает готовую последовательность команд для обеих веток. Ничего не снимает.

  кейс               ограничить план одним кейсом. Без него печатаются все.
                     Набор A/B: $AB_CASES
  --baseline-here    эталон лежит в чекауте со скриптом, а правка во втором worktree.
                     По умолчанию наоборот: эталоном считается worktree, потому что
                     у старой ветки обычно нет своей копии этого скрипта.
                     На цифры не влияет, меняется только порядок и подписи шагов.
  --decode-changed   дописать AB_DECODE_CHANGED=yes в строки прогонов. Нужен, когда
                     сравниваемая правка меняет сам декод: размер региона или
                     inSampleSize. Иначе признак годности по медиане decodeTile
                     срабатывает на замысле правки и даёт ложный вердикт.
                     То же самое делает переменная AB_DECODE_CHANGED=yes.
  -h, --help, help   эта справка

Порядок аргументов любой.
USAGE
}

# Печатает готовую последовательность команд на весь A/B, по блоку на ветку. Ничего
# не снимает.
cmd_ab_plan() {
    local only="" decode_changed="${AB_DECODE_CHANGED:-no}" baseline_here="no" arg others other
    for arg in "$@"; do
        case "$arg" in
            --decode-changed) decode_changed="yes" ;;
            --baseline-here) baseline_here="yes" ;;
            -*) die "неизвестный флаг '$arg'. ./scripts/baseline.sh ab-plan --help" ;;
            *)  is_known_case "$arg" || die "неизвестный кейс '$arg'. Набор A/B: $AB_CASES
     Справка: ./scripts/baseline.sh ab-plan --help"
                only="$arg" ;;
        esac
    done
    others="$(other_checkouts)"
    if [ -z "$others" ]; then
        echo "Второго чекаута нет. Заведи worktree ветки-эталона и повтори:"
        echo "  git worktree add ../PanZoomDemo-base <ветка-эталон>"
        return 1
    fi
    other="$(echo "$others" | head -1)"
    if [ "$(echo "$others" | wc -l | tr -d ' ')" != "1" ]; then
        echo "Чекаутов больше одного, беру первый: $other"
        echo "Остальные:"
        echo "$others" | tail -n +2 | sed 's/^/  /'
        echo
    fi

    cat <<PLAN
Порядок: сначала весь набор на эталоне, потом на ветке с правкой. Второй прогон каждого
кейса сам напечатает сравнение, потому что пара к тому моменту уже снята.

$(ab_plan_orientation_note "$baseline_here")

Сборка ставится один раз на ветку, APK от кейса к кейсу не меняется.
Перед каждым прогоном скрипт перезагружает телефон (AB_MAX_UPTIME_S=0), поэтому
состояние памяти выравнивается само и встречный порядок прогонов не обязателен.

debug с обеих сторон: у ветки-эталона release не шринкуется, и сравнивать r8-off
против r8-on сравнивалка откажется.

Запись идёт по фазам, план печатается перед стартом. Маршрут обязан совпасть, иначе
в одной сборке появятся уровни LOD, которых нет в другой, и сравнивать будет нечего.
$(ab_plan_decode_note "$decode_changed")
PLAN

    # Какой чекаут эталон, скрипт по содержимому не определяет. По умолчанию им считается
    # второй worktree, потому что в worktree обычно и выносят старую ветку, у которой
    # своей копии этого скрипта нет. Обратный расклад включается флагом.
    local base_root base_step="ШАГ 1. Ветка-эталон" fix_step="ШАГ 2. Ветка с правкой (здесь печатаются сравнения)"
    local fix_root
    if [ "$baseline_here" = "yes" ]; then
        base_root="$SCRIPT_ROOT"
        fix_root="$other"
    else
        base_root="$other"
        fix_root="$SCRIPT_ROOT"
    fi

    _ab_plan_branch "$base_root" "$(checkout_title "$base_root")" "$base_step" "$only" "$decode_changed"
    _ab_plan_branch "$fix_root" "$(checkout_title "$fix_root")" "$fix_step" "$only" "$decode_changed"

    echo "Если какой-то кейс покажет разницу на грани, переснимай его же встречно:"
    echo "  сначала свою ветку, потом эталон - и сравни два вердикта."
}

# Печатает, какой чекаут взят эталоном. Нужно потому, что по содержимому скрипт их
# не различает, и расклад надо уметь прочитать и перевернуть.
ab_plan_orientation_note() {
    if [ "$1" = "yes" ]; then
        echo "Эталоном взят чекаут со скриптом, правкой - второй worktree (флаг --baseline-here)."
    else
        echo "Эталоном считается второй worktree, правкой - чекаут со скриптом. По содержимому"
        echo "скрипт их не различает. Если у вас наоборот, добавь --baseline-here."
    fi
}

# Печатает строку про снятый признак годности. Молчит, когда флаг не передан, чтобы
# обычный план не обрастал объяснением того, чего в нём нет.
ab_plan_decode_note() {
    [ "$1" = "yes" ] || return 0
    echo
    echo "AB_DECODE_CHANGED=yes снимает с вердикта признак по медиане decodeTile: правка"
    echo "меняет сам декод, и признак сработал бы на её замысле. Годность подтверждают"
    echo "MemAvailable и лоу-мем, смотри на них внимательнее обычного."
}

# Печатает блок команд для одной ветки.
_ab_plan_branch() {
    local root="$1" title="$2" header="$3" only="$4" decode_changed="$5"
    local script="$SCRIPT_ROOT/scripts/baseline.sh" prefix="" c
    [ "$decode_changed" = "yes" ] && prefix="AB_DECODE_CHANGED=yes "
    echo "═══ $header: $title"
    echo
    echo "  cd $root"
    echo "  BUILD_TYPE=debug $script build"
    echo
    for c in $AB_CASES; do
        [ -n "$only" ] && [ "$only" != "$c" ] && continue
        echo "  # $c - $(case_mode "$c"), $(case_toggles "$c"), ассет $(case_asset "$c")"
        echo "  #   до записи: ассет $SETUP_ASSET, режим $(case_setup_mode "$c"), тумблеры $(case_toggles "$c")"
        echo "  #   в записи:  $(case_action "$c")"
        echo "  ${prefix}BUILD_TYPE=debug AB_MAX_UPTIME_S=0 $script ab $c"
        echo
    done
}

# Сравнивает два уже снятых лога. Если оба сняты с одного коммита, то сравнение идёт как
# разброс замера, а не как вердикт A/B.
cmd_ab_diff() {
    local a="$1" b="$2"
    [ -f "$a" ] || die "нет файла $a"
    [ -f "$b" ] || die "нет файла $b"
    local extra=""
    if [ "$(ab_meta_value "$a" sha)" = "$(ab_meta_value "$b" sha)" ]; then
        echo "Оба прогона с одного коммита - считаю это разбросом замера, а не A/B."
        extra="--noise"
    fi
    ab_run_compare "$a" "$b" $extra
}

# Значение ключа [$2] из сайдкара, лежащего рядом с логом [$1].
ab_meta_value() {
    local meta="${1%.logcat}.meta"
    [ -f "$meta" ] || return 0
    grep -E "^$2=" "$meta" | head -1 | cut -d= -f2-
}

# Печатает, что за конфигурация снята в уже существующих логах. Нужно для логов, для
# которых ожиданий нет.
cmd_inspect() {
    if [ $# -gt 0 ]; then
        python3 "$SCRIPT_ROOT/scripts/baseline_verify.py" --describe "$@"
    else
        # Без аргументов берём весь архив прошлых release-прогонов.
        local default_dir="$LOGS_ROOT/logs/raw_logcat/release"
        [ -d "$default_dir" ] || die "нет каталога $default_dir, укажи файлы явно"
        python3 "$SCRIPT_ROOT/scripts/baseline_verify.py" --describe "$default_dir"/*.logcat
    fi
}

# Перепроверяет пригодность самого свежего прогона кейса [$1].
cmd_verify() {
    local case_id="$1" log
    is_known_case "$case_id" || die "неизвестный кейс '$case_id'"
    log="$(latest_log_for_case "$case_id")"
    [ -n "$log" ] || die "лога для '$case_id' нет в $RAW_DIR"
    verify_run "$case_id" "$log"
}

# Переконвертирует самый свежий лог кейса [$1] в CSV.
cmd_csv() {
    local case_id="$1"
    is_known_case "$case_id" || die "неизвестный кейс '$case_id'"
    local log
    log="$(latest_log_for_case "$case_id")"
    [ -n "$log" ] || die "лога для '$case_id' нет в $RAW_DIR"
    mkdir -p "$CSV_DIR"
    python3 "$SCRIPT_ROOT/scripts/logcat_to_csv.py" "$log" -o "$CSV_DIR"
}

# Есть ли среди аргументов просьба о справке.
wants_help() {
    local arg
    for arg in "$@"; do
        case "$arg" in
            -h|--help|help) return 0 ;;
        esac
    done
    return 1
}

# Печатает справку по подкоманде [$1]. Код 1 означает, что своей справки у неё нет.
subcommand_usage() {
    case "$1" in
        doctor) cat <<'U'
./scripts/baseline.sh doctor

Проверяет окружение и ничего не меняет: adb, python3, конвертер, подключённое
устройство, установленное приложение и флаги сборки, по которым назван комплект.
Запускать перед первым прогоном и после любой правки флагов.
U
            ;;
        build) cat <<'U'
./scripts/baseline.sh build

Собирает APK и ставит на устройство. Для release дополнительно проверяет, что R8
отработал: без mapping.txt включённый флаг ничего не значит.

  BUILD_TYPE=debug|release      что собирать, по умолчанию release
  HOT_LOGS_OVERRIDE=true|false  отклонение по горячим логам. Уходит в gradle как
                                -Ppanzoom.hotLogs и попадает в имя комплекта, поэтому
                                собранное и записанное не разъезжаются.
U
            ;;
        list) cat <<'U'
./scripts/baseline.sh list

Чек-лист прогонов текущего комплекта со статусами: что снято, что осталось.
Комплект определяется коммитом и флагами сборки.
U
            ;;
        runs) cat <<'U'
./scripts/baseline.sh runs

Все снятые комплекты и чем они отличаются: коммит, горячие логи, R8, бюджеты.
Сравнивать между собой можно только комплекты с одинаковыми флагами.
U
            ;;
        run) cat <<U
./scripts/baseline.sh run <кейс> [сценарий]

Один прогон из чек-листа: предполётная проверка устройства, запись logcat,
gfxinfo, конвертация в CSV, проверка снятого лога, README.

Скрипт ведёт по фазам отсчётом и сам останавливает запись через ${RUN_DURATION_S}с.
Кейсы с гарантированным отказом, то есть naive-medium и naive-large, ведутся до Enter,
потому что отказ приходит когда придёт.

  RUN_REPEAT=<n>     сколько ГОДНЫХ прогонов собрать, сейчас ${RUN_REPEAT:-1}
  RUN_MAX_ATTEMPTS=<n> попыток на каждый годный прогон, сейчас ${RUN_MAX_ATTEMPTS:-3}
  RUN_COOLDOWN_S=<с> пауза между повторами, сейчас ${RUN_COOLDOWN_S:-60}
  RUN_DURATION_S=<с> длительность ZOOM_OUT, не меньше 60с, сейчас $RUN_DURATION_S
  RUN_HOLD=on        не останавливать запись по концу плана, ждать Enter (кроме ZOOM_OUT)
  RUN_PREFLIGHT=off  не проверять устройство перед прогоном
  RUN_RECORD=on      писать видео экрана одновременно с логом
  RUN_RECORD_BITRATE битрейт видео, сейчас $RUN_RECORD_BITRATE

Один прогон ничего не доказывает, потому что соседние прогоны на одном коде расходятся,
и в таблицы идёт медиана серии. На финальные конфигурации берут 3 повтора, на контрасты 2.

  RUN_REPEAT=3 ./scripts/baseline.sh run tiled-clg-large A

Отдаление до упора с ожиданием полной загрузки, без graphicsLayer:
  ./scripts/baseline.sh run tiled-cl-large ZOOM_OUT
  ./scripts/baseline.sh run tiled-c-large ZOOM_OUT
Оба прогона заканчиваются по таймеру, RUN_HOLD для них не применяется.
Первые 15 секунд отведены на стартовую загрузку, следующие 10 на отдаление,
а оставшееся время на догрузку. В обоих прогонах culling включён,
а отличается только LOD. В tiled-c-large LOD выключен, sampleSize остаётся равным 1.

Увеличить ожидание, сохранив одинаковую длительность обоих прогонов:
  RUN_DURATION_S=180 ./scripts/baseline.sh run tiled-cl-large ZOOM_OUT
  RUN_DURATION_S=180 ./scripts/baseline.sh run tiled-c-large ZOOM_OUT

Непригодный прогон в счёт не идёт. Он уезжает в rejected/ вместе со своим дампом кадров,
CSV и видео, и тут же переснимается, поэтому серия из трёх это три годных лога, а не три
попытки. Сколько годных прогонов кейса уже снято, показывает list.

Между повторами скрипт гасит приложение, держит паузу с отсчётом и заново гоняет
предполётную проверку.

Видео пишется через scrcpy прямо на компьютер, в records/ рядом с raw/. Через scrcpy,
а не через adb shell screenrecord, потому что на этом устройстве screenrecord падает
с сегфолтом и файла не создаёт.

Кодек занимает тот же чип, что собирает кадры, поэтому теоретически видео стоит кадров.
Важно поэтому не наличие видео, а однообразие: в одной таблице все прогоны либо с видео,
либо без. Комплект в logdata снят с видео, так что прогоны, которые кладутся рядом
с теми числами, снимаются тоже с видео.

Сценарий задаёт только жест, конфигурацию тумблеров он не меняет. Спрашивать его
имеет смысл там, где кейс допускает несколько.
$(for c in $CASES; do printf '  %-18s %s\n' "$c" "$(case_scenarios "$c")"; done)

Для сравнения двух сборок run не годится, там нужен ab.
U
            ;;
        ab) cat <<U
./scripts/baseline.sh ab <кейс>

Прогон для сравнения двух сборок. От run отличается тремя вещами: следит за
состоянием памяти устройства, держит фиксированную длительность и ведёт по фазам,
а в конце сам ищет пару в комплекте другой сборки и печатает сравнение.

  AB_DURATION=<с>         длительность записи, сейчас $AB_DURATION
  AB_SETTLE_S=<с>         пауза после перезагрузки, сейчас $AB_SETTLE_S
  AB_MAX_UPTIME_S=<с>     выше этого аптайма предлагается перезагрузка, сейчас $AB_MAX_UPTIME_S
  AB_DECODE_CHANGED=yes   снять с вердикта признак по медиане decodeTile. Нужен, когда
                          правка меняет сам декод: размер региона или inSampleSize.

Фазы записи задаёт ab_phase_plan, секунды считаются от AB_DURATION.
Маршрут пальцем обязан совпасть в обеих сборках, иначе сравнивать нечего.

Набор A/B: $AB_CASES
U
            ;;
        ab-plan) ab_plan_usage ;;
        ab-diff) cat <<'U'
./scripts/baseline.sh ab-diff <A.logcat> <B.logcat>

Сравнивает два уже снятых лога. Рядом с каждым ищет .gfx.txt и .meta.
Если оба прогона с одного коммита, считает это разбросом замера, а не A/B.

  AB_DECODE_CHANGED=yes   снять с вердикта признак по медиане decodeTile
U
            ;;
        csv) cat <<U
./scripts/baseline.sh csv <кейс>

Переконвертирует уже снятый лог кейса в CSV. Запись не идёт, прогон не нужен.

Кейсы: $CASES
U
            ;;
        verify) cat <<U
./scripts/baseline.sh verify <кейс>

Перепроверяет пригодность уже снятого прогона: тот ли ассет, тот ли режим, то ли
положение graphicsLayer, не уехала ли конфигурация в середине записи.

Кейсы: $CASES
U
            ;;
        inspect) cat <<'U'
./scripts/baseline.sh inspect [файлы]

Показывает, что за конфигурация снята в уже существующих логах.
Без аргументов берёт logs/raw_logcat/release.
U
            ;;
        readme) cat <<'U'
./scripts/baseline.sh readme

Пересобирает README комплекта по снятым логам. Ничего не записывает с устройства.
U
            ;;
        *) return 1 ;;
    esac
}

usage() {
    cat <<'USAGE'
Обвязка для снятия комплекта замеров.
Автоматизировано всё, кроме жеста на устройстве: пан и пинч-зум руками.

  ./scripts/baseline.sh doctor       проверить окружение, устройство, флаги сборки
  ./scripts/baseline.sh runs         какие комплекты уже сняты и чем отличаются
  ./scripts/baseline.sh build        собрать release и поставить на устройство
  ./scripts/baseline.sh list         чек-лист прогонов со статусами
  ./scripts/baseline.sh run <case>   один прогон: запись, gfxinfo, CSV, README
  ./scripts/baseline.sh ab <case>    прогон для сравнения двух сборок: следит за
                                     памятью устройства, держит фиксированные 90 с
                                     и печатает сравнение с прогоном другой сборки
  ./scripts/baseline.sh ab-plan [case] готовые команды на весь A/B: обе сборки,
                                     все кейсы, настоящие пути
  ./scripts/baseline.sh ab-diff <A> <B>  сравнить два уже снятых лога руками
  ./scripts/baseline.sh csv <case>   переконвертировать уже снятый лог
  ./scripts/baseline.sh verify <case> перепроверить пригодность снятого прогона
  ./scripts/baseline.sh inspect [файлы] что за конфигурация в уже снятых логах
                                       (без аргументов - logs/raw_logcat/release)
  ./scripts/baseline.sh readme       пересобрать README по снятым логам

Каталог комплекта: logs/run_<sha>_hot-<on|off>_r8-<on|off>/
Имя собирается из коммита и флагов сборки, поэтому прогоны с разными настройками
не попадают в один каталог, а уже снятые комплекты не перетираются.
Дописать в чужой комплект скрипт не даст, он сверяет run.env.
Переопределить каталог можно переменной BASELINE_DIR, тогда за смешивание отвечаешь сам.

Собрать и снять другую ветку из отдельного worktree: у неё своей копии этого скрипта
нет, поэтому запускается он отсюда, а собираемое дерево указывается переменной.
  git worktree add ../PanZoomDemo-base <ветка-эталон>
  cd ../PanZoomDemo-base && /путь/к/основному/scripts/baseline.sh build
Собирается тот чекаут, в котором стоишь, а не тот, откуда запущен скрипт; первой
строкой скрипт всегда печатает ветку и коммит собираемого дерева. Задать дерево явно
можно переменной PROJECT_ROOT.
Комплекты обеих сборок при этом складываются в один logs/, иначе пара не найдётся.
Каталог логов отдельно переопределяется переменной LOGS_ROOT.

A/B двух сборок лежит в <комплект>/ab/ и настраивается переменными AB_DURATION,
AB_SETTLE_S, AB_MAX_UPTIME_S.

Вопрос про перезагрузку сам отвечает «да» через AB_CONFIRM_S секунд (по умолчанию 30).
Отказ - одна клавиша n, без Enter.

Границы фаз отбиваются звонком терминала, конец записи двойным. Гасится AB_BELL=off,
своя команда ставится в AB_BELL_CMD (например afplay /System/Library/Sounds/Tink.aiff).

Запись A/B идёт по фазам, одинаковым для всех прогонов: переключение, пан как открылась,
отдаление до упора, пан по отдалённой, приближение до упора, пан по приближённой.
Скрипт печатает план до старта и ведёт по нему отсчётом. Доли фаз лежат в ab_phase_plan,
секунды считаются от AB_DURATION.

Правка, меняющая сам декод, то есть размер региона или inSampleSize, ломает признак
годности по медиане decodeTile. На время такой правки ставят AB_DECODE_CHANGED=yes.
Чтобы план сразу печатал команды с этой переменной, есть флаг:
  ./scripts/baseline.sh ab-plan --decode-changed [кейс]

Своя справка есть у каждой подкоманды: ./scripts/baseline.sh <подкоманда> --help

Набор кейсов A/B, которого хватает, чтобы отличить регресс в коде от состояния телефона:
  tiled-cl-large (главный), tiled-off-large, tiled-clg-large, naive-small.

Пара комплектов на цену наблюдения снимается так:
  1) HOT_LOGS = true,  R8 on  - опорный (release + HOT_LOGS_OVERRIDE=true);
  2) HOT_LOGS = false, R8 on  - цена наблюдения (release как есть).
USAGE
}

# Флаги -h, --help и слово help после любой подкоманды показывают её справку.
if [ $# -ge 2 ] && wants_help "${@:2}"; then
    subcommand_usage "$1" && exit 0
    die "у подкоманды '$1' нет своей справки. ./scripts/baseline.sh help"
fi

case "${1:-help}" in
    doctor) cmd_doctor ;;
    build)  cmd_build ;;
    list)   cmd_list ;;
    runs)   cmd_runs ;;
    run)    [ $# -ge 2 ] || die "нужен кейс. ./scripts/baseline.sh run --help"; cmd_run "$2" "${3:-}" ;;
    ab)     [ $# -ge 2 ] || die "нужен кейс. ./scripts/baseline.sh ab --help"; cmd_ab "$2" ;;
    ab-plan) shift; cmd_ab_plan "$@" ;;
    ab-diff) [ $# -ge 3 ] || die "нужны два лога. ./scripts/baseline.sh ab-diff --help"
             cmd_ab_diff "$2" "$3" ;;
    csv)    [ $# -ge 2 ] || die "нужен кейс. ./scripts/baseline.sh csv --help"; cmd_csv "$2" ;;
    verify) [ $# -ge 2 ] || die "нужен кейс. ./scripts/baseline.sh verify --help"; cmd_verify "$2" ;;
    inspect) shift; cmd_inspect "$@" ;;
    readme) require_device; write_readme; echo "README: $README" ;;
    help|-h|--help) usage ;;
    *)      die "неизвестная команда '$1'. ./scripts/baseline.sh help" ;;
esac
