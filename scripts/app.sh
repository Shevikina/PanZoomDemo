#!/bin/bash

APP="com.shevikina.panzoomdemo"

case "$1" in
    start)  adb shell am start -n "$APP/.MainActivity" ;;
    stop)   adb shell am force-stop "$APP" ;;
    check)  adb shell pidof "$APP" ;;
    re)     adb shell am force-stop "$APP" && adb shell am start -n "$APP/.MainActivity" ;;
    *)      echo "Usage: $0 [start|stop|check|re]" ;;
esac
