#!/bin/bash
#
# Управляет приложением на устройстве.
#
#   ./scripts/app.sh start    запустить
#   ./scripts/app.sh stop     снять процесс через force-stop
#   ./scripts/app.sh check    напечатать pid, если приложение живо
#   ./scripts/app.sh re       снять и запустить заново, с чистого процесса

source "$(dirname "$0")/device.sh"

APP="com.shevikina.panzoomdemo"

case "$1" in
    start)  "$ADB" shell am start -n "$APP/.MainActivity" ;;
    stop)   "$ADB" shell am force-stop "$APP" ;;
    check)  "$ADB" shell pidof "$APP" ;;
    re)     "$ADB" shell am force-stop "$APP" && "$ADB" shell am start -n "$APP/.MainActivity" ;;
    *)      echo "Usage: $0 [start|stop|check|re]" ;;
esac
