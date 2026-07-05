#!/bin/bash

APP="com.shevikina.panzoomdemo"
OUT_DIR="$(dirname "$0")/../logs/gfxinfo"

case "$1" in
    start)
        adb shell dumpsys gfxinfo "$APP" reset
        echo "gfxinfo counters reset. Start your scenario now."
        ;;
    stop)
        CASE_N="${2:?Usage: $0 stop <case_n>  (e.g. $0 stop A1)}"
        STEM="realme-RMX3371-Android-14_$(date +%Y-%m-%d_%H%M)"
        mkdir -p "$OUT_DIR"
        OUT="${OUT_DIR}/${CASE_N}_${STEM}.txt"
        adb shell dumpsys gfxinfo "$APP" > "$OUT"
        echo "Done: $OUT"
        ;;
    *)
        echo "Usage: $0 [start | stop <case_n>]"
        echo "  start        — reset gfxinfo counters before scenario"
        echo "  stop <case>  — collect gfxinfo after scenario  (e.g. $0 stop A1)"
        ;;
esac
