#!/bin/bash
#
# Общий помощник скриптов замеров: где взять adb и как называется подключённое
# устройство.
#
# Подключение одной строкой, дальше вместо `adb` пишется "$ADB".
#   source "$(dirname "$0")/device.sh"

# Путь к первому найденному adb. Пустой вывод и код 1, если ни одного нет.
#
# Ищем сами, потому что Android Studio ставит SDK к себе и в PATH его не прописывает,
# поэтому голый вызов `adb` есть не на каждой машине.
resolve_adb() {
    if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
    for candidate in \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "$HOME/Library/Android/sdk/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb"
    do
        [ -x "$candidate" ] && { echo "$candidate"; return; }
    done
    return 1
}

ADB="$(resolve_adb)" || {
    echo "adb не найден. Поставь platform-tools или задай ANDROID_HOME." >&2
    exit 1
}
# Экспортируем, потому что scrcpy ищет adb сам и читает эту переменную.
export ADB

# Значение свойства прошивки [$1]. Пустой вывод, если устройство не отвечает.
prop() {
    "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r'
}

# Имя подключённого устройства для имён файлов, например realme-RMX3371-Android-14.
#
# Собирается из свойств прошивки, поэтому прогоны с разных устройств различимы по
# имени файла и не перетирают друг друга. Пробелы в бренде и модели заменяются дефисом,
# потому что имя идёт в путь.
device_stem() {
    local brand model release
    brand="$(prop ro.product.brand)"
    model="$(prop ro.product.model)"
    release="$(prop ro.build.version.release)"
    # Свойства приходят пустыми, когда устройство не подключено или ещё не авторизовано.
    # Своё имя в этом случае честнее обрубка вида "--Android-".
    if [ -z "$brand" ] || [ -z "$model" ] || [ -z "$release" ]; then
        echo "unknown-device"
        return
    fi
    echo "${brand}-${model}-Android-${release}" | tr ' ' '-'
}
