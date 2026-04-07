# llm-guard

`llm-guard` is a local wrapper that sanitizes project context before it reaches an AI CLI.

## Scope

- Kotlin/JVM command-line app
- YAML policy file for sensitive files, content, and Kotlin symbols
- Gemini CLI as the primary validation target
- provider adapter SPI, so additional CLIs can reuse the same core

## Provider Guides

- Gemini: [docs/gemini.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/docs/gemini.md)
- Codex: [docs/codex.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/docs/codex.md)
- Current state: [docs/project-state.md](/Users/alehkastsiukovich/Development/AI/SecretsScanner/docs/project-state.md)

Each guide has separate `macOS` and `Windows` setup instructions.

## Common Concepts

What `llm-guard` does automatically:

- finds `llm-policy.yaml` by walking up from the current directory
- stages a sanitized mirror of the project into a temporary workspace
- removes blocked files and rewrites redacted files before the provider can read them
- sanitizes supported prompt arguments before launch
- can proxy interactive CLI sessions through PTY

Common wrapper flags:

- `--guard-dry-run`: stage and print the summary without starting the provider
- `--guard-approve`: continue when `confirm` rules match
- `--guard-policy <path>`: override policy discovery
- `--guard-real-executable <path>`: explicit path to the real provider executable

Provider-specific aliases also exist:

- `--guard-real-gemini <path>`
- `--guard-real-codex <path>`

## Project Layout

- CLI app: [app-cli](/Users/alehkastsiukovich/Development/AI/SecretsScanner/app-cli)
- policy engine: [core-policy](/Users/alehkastsiukovich/Development/AI/SecretsScanner/core-policy)
- guard/staging engine: [core-guard](/Users/alehkastsiukovich/Development/AI/SecretsScanner/core-guard)
- provider SPI: [adapter-spi](/Users/alehkastsiukovich/Development/AI/SecretsScanner/adapter-spi)
- Gemini adapter: [adapter-gemini-cli](/Users/alehkastsiukovich/Development/AI/SecretsScanner/adapter-gemini-cli)
- Codex adapter: [adapter-codex-cli](/Users/alehkastsiukovich/Development/AI/SecretsScanner/adapter-codex-cli)

## Development

Useful local commands:

```bash
./gradlew test
./gradlew :app-cli:installDist
./scripts/smoke-test-gemini-wrapper.sh
./gradlew :app-cli:run --args="policy validate llm-policy.example.yaml"
./gradlew :app-cli:run --args="policy print-example"
```

## Policy Example

Start from:

[llm-policy.example.yaml](/Users/alehkastsiukovich/Development/AI/SecretsScanner/llm-policy.example.yaml)
