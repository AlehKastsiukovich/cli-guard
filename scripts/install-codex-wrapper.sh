#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DEFAULT_GUARD_BIN="$REPO_ROOT/app-cli/build/install/llm-guard/bin/llm-guard"

TARGET_DIR="${HOME}/.local/bin"
TARGET_NAME="codex"
REAL_CODEX=""
GUARD_BIN="$DEFAULT_GUARD_BIN"
FORCE="0"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --guard-bin <path>      Path to llm-guard binary
  --real-codex <path>     Path to the real Codex executable
  --target-dir <path>     Directory where wrapper will be installed
  --target-name <name>    Wrapper filename, defaults to codex
  --force                 Overwrite existing wrapper
  --help                  Show this help

Example:
  ./gradlew :app-cli:installDist
  ./scripts/install-codex-wrapper.sh --real-codex "\$(command -v codex)"
EOF
}

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --guard-bin)
      [ "$#" -ge 2 ] || fail "Missing value for --guard-bin"
      GUARD_BIN="$2"
      shift 2
      ;;
    --real-codex)
      [ "$#" -ge 2 ] || fail "Missing value for --real-codex"
      REAL_CODEX="$2"
      shift 2
      ;;
    --target-dir)
      [ "$#" -ge 2 ] || fail "Missing value for --target-dir"
      TARGET_DIR="$2"
      shift 2
      ;;
    --target-name)
      [ "$#" -ge 2 ] || fail "Missing value for --target-name"
      TARGET_NAME="$2"
      shift 2
      ;;
    --force)
      FORCE="1"
      shift 1
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

[ -x "$GUARD_BIN" ] || fail "llm-guard binary is not executable: $GUARD_BIN. Run ./gradlew :app-cli:installDist first."

if [ -z "$REAL_CODEX" ]; then
  if command -v codex >/dev/null 2>&1; then
    REAL_CODEX=$(command -v codex)
  else
    fail "Could not resolve real codex executable. Pass --real-codex <path>."
  fi
fi

mkdir -p "$TARGET_DIR"
TARGET_PATH="$TARGET_DIR/$TARGET_NAME"

if [ -e "$TARGET_PATH" ] && [ "$FORCE" != "1" ]; then
  fail "Target already exists: $TARGET_PATH. Re-run with --force to overwrite."
fi

if [ "$REAL_CODEX" = "$TARGET_PATH" ]; then
  fail "Real codex path resolves to wrapper target. Pass --real-codex with the underlying executable path."
fi

cat > "$TARGET_PATH" <<EOF
#!/bin/sh
export LLM_GUARD_REAL_CODEX='$REAL_CODEX'
exec '$GUARD_BIN' codex "\$@"
EOF

chmod +x "$TARGET_PATH"

cat <<EOF
Installed Codex wrapper:
  $TARGET_PATH

Real Codex:
  $REAL_CODEX

llm-guard:
  $GUARD_BIN

Next:
  1. Ensure $TARGET_DIR is before the real Codex location in PATH.
  2. From a project with llm-policy.yaml, run: codex --guard-dry-run "test"
EOF
