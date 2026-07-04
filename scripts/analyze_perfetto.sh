#!/bin/bash
# Запускает perfetto_analyze.py в venv.
# Использование:
#   ./scripts/analyze_perfetto.sh logs/perfetto/A1_xxx.pb
#   ./scripts/analyze_perfetto.sh logs/perfetto/A1_xxx.pb logs/perfetto/E1_xxx.pb

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENV="$PROJECT_ROOT/.venv"
ANALYZE="$SCRIPT_DIR/perfetto_analyze.py"

if [ "$#" -eq 0 ]; then
    echo "Использование: $0 <file.pb> [file2.pb]" >&2
    exit 1
fi

# Создать venv и установить perfetto, если ещё не сделано
if [ ! -f "$VENV/bin/python3" ]; then
    echo "Создаю venv: $VENV"
    python3 -m venv "$VENV"
fi

if ! "$VENV/bin/python3" -c "import perfetto" 2>/dev/null; then
    echo "Устанавливаю perfetto в venv..."
    "$VENV/bin/pip" install --quiet perfetto
fi

"$VENV/bin/python3" "$ANALYZE" "$@"
