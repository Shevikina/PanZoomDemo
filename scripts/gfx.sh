#!/bin/bash
#
# Снимает счётчики кадров через `dumpsys gfxinfo`.
#
#   ./scripts/gfx.sh start          сбросить счётчики перед сценарием
#   ./scripts/gfx.sh stop <кейс>    снять дамп после сценария
#
# Счётчики накопительные, поэтому без сброса дамп смешивает нужный сценарий
# с тем, что происходило до него.

source "$(dirname "$0")/device.sh"

APP="com.shevikina.panzoomdemo"
OUT_DIR="$(dirname "$0")/../logs/gfxinfo"

case "$1" in
    start)
        "$ADB" shell dumpsys gfxinfo "$APP" reset
        echo "gfxinfo counters reset. Start your scenario now."
        ;;
    stop)
        CASE_N="${2:?Usage: $0 stop <case_n>  (e.g. $0 stop A1)}"
        STEM="$(device_stem)_$(date +%Y-%m-%d_%H%M)"
        mkdir -p "$OUT_DIR"
        OUT="${OUT_DIR}/${CASE_N}_${STEM}.txt"
        "$ADB" shell dumpsys gfxinfo "$APP" > "$OUT"
        echo "Done: $OUT"
        ;;
    *)
        echo "Usage: $0 [start | stop <case_n>]"
        echo "  start        - reset gfxinfo counters before scenario"
        echo "  stop <case>  - collect gfxinfo after scenario  (e.g. $0 stop A1)"
        ;;
esac
