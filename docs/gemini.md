# Gemini Guide

This is the primary manual test path for `llm-guard`.

## Daily Flow

1. Build `llm-guard`
2. Install the `gemini` wrapper
3. Add `llm-policy.yaml` to the root of the project you want to protect
4. Run `gemini` as usual

Normal usage:

```bash
gemini -p "Explain billing flow"
gemini
```

`--guard-dry-run` is optional. Use it only when you want to inspect what would be staged without starting the real Gemini CLI.

## macOS

Build:

```bash
export LLM_GUARD_REPO=/path/to/SecretsScanner
cd "$LLM_GUARD_REPO"
./gradlew :app-cli:installDist
```

Install the Gemini wrapper:

```bash
./scripts/install-gemini-wrapper.sh --real-gemini "$(command -v gemini)"
```

Put the wrapper first in `PATH`:

```bash
export PATH="$HOME/.local/bin:$PATH"
which gemini
```

Use it inside a protected project:

```bash
cd /path/to/android-project
cp "$LLM_GUARD_REPO/llm-policy.example.yaml" ./llm-policy.yaml
gemini -p "Explain billing flow"
gemini
```

## Windows

Build:

```powershell
$LlmGuardRepo = "C:\path\to\SecretsScanner"
Set-Location $LlmGuardRepo
.\gradlew.bat :app-cli:installDist
```

Install the Gemini wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-gemini-wrapper.ps1
```

If `gemini` is not already in `PATH`, pass it explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-gemini-wrapper.ps1 -RealGemini "C:\path\to\gemini.ps1"
```

Add `$HOME\bin` to `PATH` and reopen the terminal.

Use it inside a protected project:

```powershell
cd C:\path\to\android-project
Copy-Item "$LlmGuardRepo\llm-policy.example.yaml" .\llm-policy.yaml
gemini -p "Explain billing flow"
gemini
```

## Smoke Test

If you want to verify the wrapper logic locally before using the real Gemini backend:

```powershell
.\gradlew.bat :app-cli:installDist
bash ./scripts/smoke-test-gemini-wrapper.sh
```

This runs a fake `gemini` executable behind `llm-guard` and checks that:

- the sample policy is loaded
- a sanitized workspace is staged
- the wrapper rewrites the Gemini invocation instead of calling the original project directly

## What You Will See

When the wrapper is active, the same terminal prints guard information before Gemini starts.

Example:

```text
Gemini proxy summary:
  Policy: /path/to/project/llm-policy.yaml
  Project root: /path/to/project
  Staged workspace: /tmp/llm-guard-workspace-...
Starting interactive gemini proxy session.
```

If your input was changed before forwarding:

```text
[llm-guard] interactive input was sanitized before forwarding.
```

If your input was blocked:

```text
[llm-guard] interactive input blocked and was not forwarded.
```

If files change while `gemini` is already running, the wrapper also prints live sync updates in the same terminal.

Example:

```text
[llm-guard] live workspace sync active for gemini.
[llm-guard] live sync updated src: redacted, cacheHits=0.
```

This means:

- safe file changes can be reflected during the running session
- if a previously safe subtree becomes sensitive, the staged overlay is replaced with a sanitized version
- if a redacted subtree becomes safe again, it can return to pass-through mode

## Wrapper Flags

- `--guard-dry-run`: stage and print the summary without starting Gemini
- `--guard-approve`: continue when `confirm` rules match
- `--guard-policy <path>`: override policy discovery
- `--guard-real-gemini <path>`: explicit path to the real Gemini executable

Optional local detector backends:

- `LLM_GUARD_PRIVACY_FILTER_BIN`: explicit path to the local `opf` executable
- `LLM_GUARD_GITLEAKS_BIN`: explicit path to the local `gitleaks` executable

If these are set and the corresponding detector is enabled in `llm-policy.yaml`, prompt and interactive text can be sanitized on the fly before forwarding to Gemini.

## Direct Invocation

If you do not want to override the shell command yet:

```bash
llm-guard gemini -p "Explain the billing flow"
llm-guard gemini
```
