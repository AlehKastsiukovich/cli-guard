# Project State

## Summary

`llm-guard` is a local Kotlin/JVM wrapper that sanitizes repository context before it reaches an AI CLI.

Current product direction:

- primary validation target: Gemini CLI
- secondary integrated provider: Codex CLI
- long-term direction: provider-agnostic local guard with reusable core

## Implemented

### Core

- YAML policy loading from `llm-policy.yaml`
- rule evaluation for:
  - path-based matching
  - content regex matching
  - secret detection
  - Kotlin symbol matching by package, class, function, annotation
- actions:
  - `allow`
  - `block`
  - `redact`
  - `confirm`
  - `summarize`

### Workspace Protection

- sanitized workspace staging into a temporary directory
- blocked files omitted from staged workspace
- redacted files rewritten before provider launch
- support for staging:
  - whole project content
  - external include directories
  - external include files

### CLI Integration

- headless provider invocation through `run`
- transparent provider wrapper mode:
  - `llm-guard gemini ...`
  - `llm-guard codex ...`
- interactive PTY proxy support for interactive sessions
- interactive input sanitization for ordinary text lines
- slash-style commands such as `/help` bypass prompt sanitization

### Providers

- `gemini-cli`
  - headless prompt rewrite for `-p` / `--prompt`
  - interactive Gemini wrapper flow
- `codex-cli`
  - interactive Codex wrapper flow
  - headless `exec` support
  - path argument rewrite for `-C`, `--add-dir`, `-i`

### Installation Helpers

- macOS/Linux Gemini wrapper install script:
  - [scripts/install-gemini-wrapper.sh](/Users/alehkastsiukovich/Development/AI/SecretsScanner/scripts/install-gemini-wrapper.sh)
- Windows Gemini wrapper install script:
  - [scripts/install-gemini-wrapper.ps1](/Users/alehkastsiukovich/Development/AI/SecretsScanner/scripts/install-gemini-wrapper.ps1)
- macOS/Linux Codex wrapper install script:
  - [scripts/install-codex-wrapper.sh](/Users/alehkastsiukovich/Development/AI/SecretsScanner/scripts/install-codex-wrapper.sh)
- Windows Codex wrapper install script:
  - [scripts/install-codex-wrapper.ps1](/Users/alehkastsiukovich/Development/AI/SecretsScanner/scripts/install-codex-wrapper.ps1)
- Windows setup instructions documented in provider guides

## Documentation Layout

- overview:
  - [README.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/README.md)
- Gemini guide:
  - [docs/gemini.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/docs/gemini.md)
- Codex guide:
  - [docs/codex.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/docs/codex.md)
- current state:
  - [docs/project-state.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/docs/project-state.md)

## Verified

Confirmed locally:

- Gemini smoke test passes through fake provider wrapper:
  - [scripts/smoke-test-gemini-wrapper.sh](/Users/alehkastsiukovich/Development/AI/SecretsScanner/scripts/smoke-test-gemini-wrapper.sh)
- sanitized workspace is created
- redacted Kotlin file is rewritten before provider execution
- provider process is started from staged workspace, not original repo
- guard summary is printed before provider execution
- Codex `exec` contract was exercised earlier in this environment

## Known Limitations

- Gemini has not yet been validated end-to-end against a real live Gemini session in this current phase
- interactive PTY proxy is line-oriented, not a full terminal emulator
- advanced terminal editing behavior may differ from native CLI behavior
- full Gradle test execution is currently environment-sensitive in restricted sandboxes because of Gradle file lock/socket behavior

## Recommended Manual Test Order

1. Start with the Gemini smoke test
2. Install the Gemini wrapper locally
3. Add a project-specific `llm-policy.yaml`
4. Run `gemini -p "..."` in a protected project
5. Run interactive `gemini`
6. Only after Gemini validation, return to Codex and broader provider support

## Next Recommended Engineering Steps

1. Validate Gemini against a real target repository and real developer flow
2. Improve startup UX with a shorter stable banner
3. Add better audit/logging for what was blocked or redacted
4. Revisit PTY proxy fidelity if native CLI behavior feels off
5. Harden Windows install experience with dedicated helper script if needed
