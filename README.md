# llm-guard

`llm-guard` is a provider-agnostic local wrapper for AI CLIs.

The initial scope of this repository is:

- a Kotlin/JVM command-line entry point;
- a YAML policy file for sensitive files, content, and Kotlin symbols;
- a provider adapter SPI so the Gemini CLI integration is only the first adapter.

Current commands:

```bash
./gradlew :app-cli:run --args='gemini --guard-dry-run -p "Explain the billing flow"'
./gradlew :app-cli:run --args='gemini -p "Explain the billing flow" --output-format json'
./gradlew :app-cli:run --args="policy validate llm-policy.example.yaml"
./gradlew :app-cli:run --args="policy print-example"
./gradlew :app-cli:run --args='run --policy llm-policy.example.yaml --prompt "review this" --attach app/src/main/kotlin/Foo.kt --dry-run'
./gradlew :app-cli:run --args='run --adapter gemini-cli --policy llm-policy.example.yaml --prompt "Explain the billing flow" --attach app/src/main/kotlin/Foo.kt -- --output-format json'
```

## Transparent Gemini wrapper

The intended daily-driver mode is the `gemini` command exposed by `llm-guard`:

```bash
llm-guard gemini -p "Explain the billing flow"
llm-guard gemini
```

What it does automatically:

- finds `llm-policy.yaml` by walking up from the current directory;
- stages a sanitized mirror of the whole project into a temporary workspace;
- removes blocked files and rewrites redacted files before Gemini can read them;
- rewrites `-p` / `--prompt` for headless mode if the prompt itself matches the policy;
- proxies interactive TTY sessions through PTY and sanitizes ordinary prompt lines before they reach Gemini;
- launches the real Gemini CLI from the sanitized workspace.

Interactive note:

- ordinary prompt lines are filtered before forwarding;
- slash commands such as `/help` are passed through unchanged;
- Gemini still works against the sanitized workspace, so file tools only see the mirrored project.

Wrapper-specific flags:

- `--guard-dry-run`: stage and print the summary without starting Gemini.
- `--guard-approve`: continue when `confirm` rules match.
- `--guard-policy <path>`: override policy discovery.
- `--guard-real-gemini <path>`: explicit path to the real Gemini executable.

## Local install

Build the distributable binary:

```bash
./gradlew :app-cli:installDist
```

This creates:

[`llm-guard`](/Users/alehkastsiukovich/Development/AI/SecretsScanner/app-cli/build/install/llm-guard/bin/llm-guard)

Install the shell wrapper:

```bash
./scripts/install-gemini-wrapper.sh --real-gemini "$(command -v gemini)"
```

By default it writes:

`~/.local/bin/gemini`

That wrapper:

- stores the path to the real Gemini executable in `LLM_GUARD_REAL_GEMINI`
- calls `llm-guard gemini "$@"`

Then make sure `~/.local/bin` is before the real Gemini location in `PATH`.

Example:

```bash
export PATH="$HOME/.local/bin:$PATH"
which gemini
gemini --guard-dry-run -p "Explain the billing flow"
```

After that, normal `gemini ...` usage goes through the guard first.

`run` stages sanitized artifacts into a temporary directory and exposes them to the provider command through:

- `LLM_GUARD_PROMPT_FILE`
- `LLM_GUARD_ATTACHMENTS_DIR`
- `LLM_GUARD_STAGE_DIR`

`gemini-cli` is the first provider-specific adapter. It supports both headless `-p` mode and interactive PTY-proxied sessions from the staged sanitized workspace.
