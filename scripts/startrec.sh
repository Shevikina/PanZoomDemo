#!/bin/bash
#
# Пишет видео экрана через scrcpy. Пишет, пока не остановишь Ctrl-C.
#
#   ./scripts/startrec.sh <кейс>
#
# adb нужен, чтобы спросить у устройства его имя. Саму картинку снимает scrcpy.

source "$(dirname "$0")/device.sh"

CASE_N="${1:?Usage: $0 <case_n>  (e.g. $0 A2)}"
STEM="$(device_stem)_$(date +%Y-%m-%d_%H%M)"
OUT="$(dirname "$0")/../logs/records/${CASE_N}_${STEM}.mp4"

# Каталог найденного adb добавляем в PATH, потому что scrcpy зовёт adb и через
# переменную ADB, и просто по имени.
PATH="$(dirname "$ADB"):$PATH" scrcpy --record "$OUT" --no-audio
