#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DEFAULT_GUARD_BIN="$REPO_ROOT/app-cli/build/install/llm-guard/bin/llm-guard"

TARGET_DIR="${HOME}/.local/bin"
TARGET_NAME="gemini"
REAL_GEMINI=""
GUARD_BIN="$DEFAULT_GUARD_BIN"
FORCE="0"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --guard-bin <path>      Path to llm-guard binary
  --real-gemini <path>    Path to the real Gemini executable
  --target-dir <path>     Directory where wrapper will be installed
  --target-name <name>    Wrapper filename, defaults to gemini
  --force                 Overwrite existing wrapper
  --help                  Show this help

Example:
  ./gradlew :app-cli:installDist
  ./scripts/install-gemini-wrapper.sh --real-gemini "\$(command -v gemini)"
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
    --real-gemini)
      [ "$#" -ge 2 ] || fail "Missing value for --real-gemini"
      REAL_GEMINI="$2"
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

if [ -z "$REAL_GEMINI" ]; then
  if command -v gemini >/dev/null 2>&1; then
    REAL_GEMINI=$(command -v gemini)
  else
    fail "Could not resolve real gemini executable. Pass --real-gemini <path>."
  fi
fi

mkdir -p "$TARGET_DIR"
TARGET_PATH="$TARGET_DIR/$TARGET_NAME"

if [ -e "$TARGET_PATH" ] && [ "$FORCE" != "1" ]; then
  fail "Target already exists: $TARGET_PATH. Re-run with --force to overwrite."
fi

if [ "$REAL_GEMINI" = "$TARGET_PATH" ]; then
  fail "Real gemini path resolves to wrapper target. Pass --real-gemini with the underlying executable path."
fi

cat > "$TARGET_PATH" <<EOF
#!/bin/sh
export LLM_GUARD_REAL_GEMINI='$REAL_GEMINI'
exec '$GUARD_BIN' gemini "\$@"
EOF

chmod +x "$TARGET_PATH"

cat <<EOF
Installed Gemini wrapper:
  $TARGET_PATH

Real Gemini:
  $REAL_GEMINI

llm-guard:
  $GUARD_BIN

Next:
  1. Ensure $TARGET_DIR is before the real Gemini location in PATH.
  2. From a project with llm-policy.yaml, run: gemini --guard-dry-run -p "test"
EOF
