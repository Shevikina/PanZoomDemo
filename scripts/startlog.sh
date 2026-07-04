#!/bin/bash

CASE_N="${1:?Usage: $0 <case_n>  (e.g. $0 A2)}"
STEM="realme-RMX3371-Android-14_$(date +%Y-%m-%d_%H%M)"
OUT="$(dirname "$0")/../logs/raw_logcat/release/${CASE_N}_${STEM}.logcat"

adb logcat -c
adb logcat -v threadtime -T "$(date +%s.000)" > "$OUT"
