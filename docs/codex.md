# Codex Guide

`codex-cli` support is already wired into the same core, but it is currently secondary to the Gemini validation path.

## Daily Flow

Normal usage:

```bash
codex
codex exec "Explain billing flow"
```

`--guard-dry-run` is optional. Use it only when you want to inspect what would be staged without starting the real Codex CLI.

## macOS

Build:

```bash
export LLM_GUARD_REPO=/path/to/SecretsScanner
cd "$LLM_GUARD_REPO"
./gradlew :app-cli:installDist
```

Install the Codex wrapper:

```bash
./scripts/install-codex-wrapper.sh --real-codex "$(command -v codex)"
```

Put the wrapper first in `PATH`:

```bash
export PATH="$HOME/.local/bin:$PATH"
which codex
```

Use it inside a protected project:

```bash
cd /path/to/android-project
cp "$LLM_GUARD_REPO/llm-policy.example.yaml" ./llm-policy.yaml
codex
codex exec "Explain billing flow"
```

## Windows

Build:

```powershell
$LlmGuardRepo = "C:\path\to\SecretsScanner"
Set-Location $LlmGuardRepo
.\gradlew.bat :app-cli:installDist
```

Install the Codex wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-codex-wrapper.ps1
```

If `codex` is not already in `PATH`, pass it explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-codex-wrapper.ps1 -RealCodex "C:\path\to\codex.exe"
```

Add `$HOME\bin` to `PATH` and reopen the terminal.

Use it inside a protected project:

```powershell
cd C:\path\to\android-project
Copy-Item "$LlmGuardRepo\llm-policy.example.yaml" .\llm-policy.yaml
codex
codex exec "Explain billing flow"
```

## Wrapper Flags

- `--guard-dry-run`: stage and print the summary without starting Codex
- `--guard-approve`: continue when `confirm` rules match
- `--guard-policy <path>`: override policy discovery
- `--guard-real-codex <path>`: explicit path to the real Codex executable

## Direct Invocation

If you do not want to override the shell command yet:

```bash
llm-guard codex
llm-guard codex exec "Explain billing flow"
```
