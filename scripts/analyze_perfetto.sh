#!/bin/bash
#
# Запускает perfetto_analyze.py в venv проекта. Сам venv создаёт при первом вызове
# и ставит в него пакет perfetto.
#
#   ./scripts/analyze_perfetto.sh <файл.pb> [файл2.pb]
#
# Два файла означают сравнение, их разбирает сам perfetto_analyze.py.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENV="$PROJECT_ROOT/.venv"
ANALYZE="$SCRIPT_DIR/perfetto_analyze.py"

if [ "$#" -eq 0 ]; then
    echo "Использование: $0 <file.pb> [file2.pb]" >&2
    exit 1
fi

if [ ! -f "$VENV/bin/python3" ]; then
    echo "Создаю venv: $VENV"
    python3 -m venv "$VENV"
fi

if ! "$VENV/bin/python3" -c "import perfetto" 2>/dev/null; then
    echo "Устанавливаю perfetto в venv..."
    "$VENV/bin/pip" install --quiet perfetto
fi

"$VENV/bin/python3" "$ANALYZE" "$@"
