# llm-guard

`llm-guard` is a provider-agnostic local wrapper for AI CLIs.

The initial scope of this repository is:

- a Kotlin/JVM command-line entry point;
- a YAML policy file for sensitive files, content, and Kotlin symbols;
- provider adapters for Gemini CLI and Codex CLI.

## Quick Start

The target day-to-day flow is simple:

1. Build `llm-guard`
2. Put a `gemini` or `codex` wrapper in your user `PATH`
3. Add `llm-policy.yaml` to the root of the project you want to protect
4. Run your CLI as usual

For Codex, the real day-to-day flow is:

```bash
codex
codex exec "Explain billing flow"
```

`--guard-dry-run` is optional. Use it only when you want to inspect what would be staged without starting the real provider.

### macOS

Build:

```bash
export LLM_GUARD_REPO=/path/to/SecretsScanner
cd "$LLM_GUARD_REPO"
./gradlew :app-cli:installDist
```

Install the wrapper you want to test:

```bash
./scripts/install-gemini-wrapper.sh --real-gemini "$(command -v gemini)"
./scripts/install-codex-wrapper.sh --real-codex "$(command -v codex)"
```

Put the wrapper first in `PATH`:

```bash
export PATH="$HOME/.local/bin:$PATH"
which gemini
which codex
```

Use it inside a protected project:

```bash
cd /path/to/android-project
cp "$LLM_GUARD_REPO/llm-policy.example.yaml" ./llm-policy.yaml
codex
codex exec "Explain billing flow"
codex --guard-dry-run "Explain billing flow"
gemini
gemini --guard-dry-run -p "Explain billing flow"
```

### Windows

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

Find the real executable you want to wrap:

```powershell
$RealGemini = (Get-Command gemini).Source
$RealCodex = (Get-Command codex).Source
```

Create `gemini.cmd` in `$HOME\bin`:

```powershell
@"
@echo off
set "LLM_GUARD_REAL_GEMINI=$RealGemini"
call "$LlmGuardRepo\app-cli\build\install\llm-guard\bin\llm-guard.bat" gemini %*
"@ | Set-Content "$HOME\bin\gemini.cmd"
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
codex --guard-dry-run "Explain billing flow"
gemini
gemini --guard-dry-run -p "Explain billing flow"
```

## What You Will See

When the wrapper is active, the same terminal prints guard information before the provider starts.

Example:

```text
Codex proxy summary:
  Policy: /path/to/project/llm-policy.yaml
  Project root: /path/to/project
  Staged workspace: /tmp/llm-guard-workspace-...
Starting interactive codex proxy session.
```

If your input was changed before forwarding:

```text
[llm-guard] interactive input was sanitized before forwarding.
```

If your input was blocked:

```text
[llm-guard] interactive input blocked and was not forwarded.
```

## Transparent Provider Wrappers

The intended daily-driver mode is the provider command exposed by `llm-guard`:

```bash
llm-guard gemini -p "Explain the billing flow"
llm-guard gemini
llm-guard codex exec "Explain the billing flow"
llm-guard codex
```

What it does automatically:

- finds `llm-policy.yaml` by walking up from the current directory;
- stages a sanitized mirror of the whole project into a temporary workspace;
- removes blocked files and rewrites redacted files before the provider can read them;
- rewrites supported prompt arguments before launch if the prompt itself matches the policy;
- proxies interactive TTY sessions through PTY and sanitizes ordinary prompt lines before they reach the provider;
- launches the real CLI from the sanitized workspace.

Interactive note:

- ordinary prompt lines are filtered before forwarding;
- slash commands such as `/help` are passed through unchanged;
- the provider still works against the sanitized workspace, so file tools only see the mirrored project.

Wrapper-specific flags:

- `--guard-dry-run`: stage and print the summary without starting the provider. Optional troubleshooting mode, not the main workflow.
- `--guard-approve`: continue when `confirm` rules match.
- `--guard-policy <path>`: override policy discovery.
- `--guard-real-gemini <path>`: explicit path to the real Gemini executable.
- `--guard-real-codex <path>`: explicit path to the real Codex executable.

## Local install

Build the distributable binary:

```bash
./gradlew :app-cli:installDist
```

This creates:

[`llm-guard`](/Users/alehkastsiukovich/Development/AI/SecretsScanner/app-cli/build/install/llm-guard/bin/llm-guard)

Install the shell wrapper on macOS/Linux:

```bash
./scripts/install-gemini-wrapper.sh --real-gemini "$(command -v gemini)"
./scripts/install-codex-wrapper.sh --real-codex "$(command -v codex)"
```

By default it writes:

`~/.local/bin/gemini`
`~/.local/bin/codex`

That wrapper:

- stores the path to the real Gemini executable in `LLM_GUARD_REAL_GEMINI`
- calls `llm-guard gemini "$@"`
- stores the path to the real Codex executable in `LLM_GUARD_REAL_CODEX`
- calls `llm-guard codex "$@"`

Then make sure `~/.local/bin` is before the real provider location in `PATH`.

Example:

```bash
export PATH="$HOME/.local/bin:$PATH"
which gemini
which codex
gemini --guard-dry-run -p "Explain the billing flow"
codex --guard-dry-run "Explain the billing flow"
```

After that, normal `gemini ...` or `codex ...` usage goes through the guard first.

## Development Commands

Useful local commands:

```bash
./gradlew test
./gradlew :app-cli:run --args='gemini --guard-dry-run -p "Explain the billing flow"'
./gradlew :app-cli:run --args='gemini -p "Explain the billing flow" --output-format json'
./gradlew :app-cli:run --args='codex --guard-dry-run "Explain the billing flow"'
./gradlew :app-cli:run --args='codex exec "Explain the billing flow" --json'
./gradlew :app-cli:run --args="policy validate llm-policy.example.yaml"
./gradlew :app-cli:run --args="policy print-example"
./gradlew :app-cli:run --args='run --policy llm-policy.yaml --prompt "review this" --attach app/src/main/kotlin/Foo.kt --dry-run'
./gradlew :app-cli:run --args='run --adapter gemini-cli --policy llm-policy.yaml --prompt "Explain the billing flow" --attach app/src/main/kotlin/Foo.kt -- --output-format json'
./gradlew :app-cli:run --args='run --adapter codex-cli --policy llm-policy.yaml --prompt "Explain the billing flow" --attach app/src/main/kotlin/Foo.kt -- exec --json'
```

`run` stages sanitized artifacts into a temporary directory and exposes them to the provider command through:

- `LLM_GUARD_PROMPT_FILE`
- `LLM_GUARD_ATTACHMENTS_DIR`
- `LLM_GUARD_STAGE_DIR`

`gemini-cli` and `codex-cli` are the current provider-specific adapters. Both use the same staging and policy engine; Gemini is wired around `-p`, while Codex is wired around its default interactive mode and `exec`.
