#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
GUARD_BIN="${LLM_GUARD_BIN:-$REPO_ROOT/app-cli/build/install/llm-guard/bin/llm-guard}"

usage() {
  cat <<EOF
Usage:
  $(basename "$0")

Runs a local smoke test for the Gemini wrapper without calling the real Gemini backend.

Prerequisite:
  ./gradlew :app-cli:installDist
EOF
}

if [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

[ -x "$GUARD_BIN" ] || {
  printf '%s\n' "llm-guard binary is not executable: $GUARD_BIN" >&2
  printf '%s\n' "Run ./gradlew :app-cli:installDist first." >&2
  exit 1
}

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/llm-guard-gemini-smoke.XXXXXX")
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

PROJECT_DIR="$TMP_DIR/project"
mkdir -p "$PROJECT_DIR/app/src/main/kotlin/demo"
cp "$REPO_ROOT/llm-policy.example.yaml" "$PROJECT_DIR/llm-policy.yaml"

cat > "$PROJECT_DIR/app/src/main/kotlin/demo/PaymentSignatureGenerator.kt" <<'EOF'
package com.company.billing.internal

class PaymentSignatureGenerator {
    fun generateHmac(raw: String): String {
        val apiKey = "1234567890abcdef1234567890abcdef"
        return raw + apiKey
    }
}
EOF

FAKE_GEMINI="$TMP_DIR/fake-gemini.sh"
cat > "$FAKE_GEMINI" <<'EOF'
#!/bin/sh
set -eu
printf 'FAKE_GEMINI_CWD=%s\n' "$(pwd)"
printf 'FAKE_GEMINI_ARGS=%s\n' "$*"
EOF
chmod +x "$FAKE_GEMINI"

cd "$PROJECT_DIR"
FIRST_OUTPUT=$(
  LLM_GUARD_REAL_GEMINI="$FAKE_GEMINI" \
    "$GUARD_BIN" gemini -p "Explain billing flow" 2>&1
)

SECOND_OUTPUT=$(
  LLM_GUARD_REAL_GEMINI="$FAKE_GEMINI" \
    "$GUARD_BIN" gemini -p "Explain billing flow" 2>&1
)

printf '%s\n' "$FIRST_OUTPUT"
printf '\n%s\n' "--- second run ---"
printf '%s\n' "$SECOND_OUTPUT"

printf '%s\n' "$FIRST_OUTPUT" | grep -q "Gemini proxy summary:"
printf '%s\n' "$FIRST_OUTPUT" | grep -q "Files pass-through:"
printf '%s\n' "$FIRST_OUTPUT" | grep -q "Files reused from cache: 0"
printf '%s\n' "$FIRST_OUTPUT" | grep -q "FAKE_GEMINI_CWD="
printf '%s\n' "$FIRST_OUTPUT" | grep -q "FAKE_GEMINI_ARGS=-p"
printf '%s\n' "$SECOND_OUTPUT" | grep -Eq "Files reused from cache: [1-9][0-9]*"

printf '\n%s\n' "Gemini smoke test passed."
