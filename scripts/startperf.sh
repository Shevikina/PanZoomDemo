#!/bin/bash

#  Что делает скрипт:
#  1. Принимает номер сценария обязательным аргументом (аналог startrec/startlog) — startperf A0
#  2. Формирует STEM в том же формате, что и другие скрипты
#  3. Создаёт logs/perfetto/ если его нет
#  4. Запускает adb shell perfetto с конфигом из п. 8.2 ровно на 15 секунд
#  5. Делает adb pull трейса в logs/perfetto/A0_realme-RMX3371-Android-14_<timestamp>.pb
#  6. Печатает путь и напоминание открыть в https://ui.perfetto.dev

CASE_N="${1:?Usage: $0 <case_n>  (e.g. $0 A0)}"
STEM="realme-RMX3371-Android-14_$(date +%Y-%m-%d_%H%M)"
OUT_DIR="$(dirname "$0")/../logs/perfetto"
OUT="${OUT_DIR}/${CASE_N}_${STEM}.pb"

mkdir -p "$OUT_DIR"

REMOTE="/data/misc/perfetto-traces/trace.pb"
adb shell mkdir -p /data/misc/perfetto-traces

echo "Recording 15s Perfetto trace → $OUT"

adb shell perfetto -o "$REMOTE" \
  -c - --txt <<EOF
duration_ms: 15000
buffers { size_kb: 131072 }
data_sources { config { name: "linux.ftrace"
  ftrace_config {
    ftrace_events: "sched/sched_switch"
    ftrace_events: "power/cpu_frequency"
    ftrace_events: "ftrace/print"
    atrace_categories: "app"
    atrace_categories: "view"
    atrace_categories: "am"
    atrace_apps: "com.shevikina.panzoomdemo"
  }
}}
data_sources { config { name: "linux.process_stats"
  process_stats_config { scan_all_processes_on_start: true }
}}
data_sources { config { name: "android.gpu.memory" } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
EOF

if ! adb shell "[ -f $REMOTE ]"; then
    echo "Error: perfetto did not write $REMOTE — check 'adb shell perfetto --help' and SELinux." >&2
    exit 1
fi

adb pull "$REMOTE" "$OUT"
echo "Done: $OUT"
echo "Open at https://ui.perfetto.dev"
