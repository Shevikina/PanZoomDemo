#!/bin/bash
#
# Снимает 15-секундный трейс Perfetto и забирает его на компьютер.
#
#   ./scripts/startperf.sh <кейс>
#
# Приложение скрипт не трогает, потому что режим, ассет и тумблеры выставляются
# до запуска.
#
# Категории atrace app, view и am обязательны, потому что без них в трейсе не будет
# ни updateTiles, ни decodeTile, ни секций рекомпозиции.

source "$(dirname "$0")/device.sh"

CASE_N="${1:?Usage: $0 <case_n>  (e.g. $0 A0)}"
STEM="$(device_stem)_$(date +%Y-%m-%d_%H%M)"
OUT_DIR="$(dirname "$0")/../logs/perfetto"
OUT="${OUT_DIR}/${CASE_N}_${STEM}.pb"

mkdir -p "$OUT_DIR"

REMOTE="/data/misc/perfetto-traces/trace.pb"
"$ADB" shell mkdir -p /data/misc/perfetto-traces

echo "Recording 15s Perfetto trace → $OUT"
echo
echo "Приложение скрипт не трогает: оно должно быть УЖЕ в нужном состоянии."
echo "Жест начинать сразу и вести все 15 секунд без пауз."
echo

# Пауза перед стартом в секундах. Нужна, чтобы успеть взять телефон в руки, потому
# что perfetto начинает писать сразу и первые секунды трейса иначе уходят на это.
PREROLL="${PERF_PREROLL:-5}"
while [ "$PREROLL" -gt 0 ]; do
    printf "\rБери телефон, старт через %d... " "$PREROLL"
    sleep 1
    PREROLL=$((PREROLL - 1))
done
printf "\rПОШЛА ЗАПИСЬ, веди жест 15 секунд\033[K\n"

# Отсчёт записи идёт фоном, потому что сам perfetto держит терминал все 15 секунд.
(
    LEFT=15
    while [ "$LEFT" -gt 0 ]; do
        printf "\r  ещё %2dс\033[K" "$LEFT"
        sleep 1
        LEFT=$((LEFT - 1))
    done
    printf "\r  запись окончена\033[K\n"
) &
COUNTER_PID=$!

"$ADB" shell perfetto -o "$REMOTE" \
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

wait "$COUNTER_PID" 2>/dev/null

if ! "$ADB" shell "[ -f $REMOTE ]"; then
    echo "Error: perfetto did not write $REMOTE - check 'adb shell perfetto --help' and SELinux." >&2
    exit 1
fi

"$ADB" pull "$REMOTE" "$OUT"
echo "Done: $OUT"
echo "Open at https://ui.perfetto.dev"
