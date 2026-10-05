#!/bin/bash
#
# Пишет logcat прогона в файл. Пишет, пока не остановишь Ctrl-C.
#
#   ./scripts/startlog.sh <кейс>

source "$(dirname "$0")/device.sh"

CASE_N="${1:?Usage: $0 <case_n>  (e.g. $0 A2)}"
STEM="$(device_stem)_$(date +%Y-%m-%d_%H%M)"
OUT="$(dirname "$0")/../logs/raw_logcat/release/${CASE_N}_${STEM}.logcat"

# Сначала чистим буфер, потом читаем от текущей секунды, чтобы в файл попало
# только то, что случилось после запуска.
"$ADB" logcat -c
"$ADB" logcat -v threadtime -T "$(date +%s.000)" > "$OUT"
