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

Create a wrapper directory:

```powershell
New-Item -ItemType Directory -Force "$HOME\bin" | Out-Null
```

Find the real Codex executable:

```powershell
$RealCodex = (Get-Command codex).Source
```

Create `codex.cmd` in `$HOME\bin`:

```powershell
@"
@echo off
set "LLM_GUARD_REAL_CODEX=$RealCodex"
call "$LlmGuardRepo\app-cli\build\install\llm-guard\bin\llm-guard.bat" codex %*
"@ | Set-Content "$HOME\bin\codex.cmd"
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
