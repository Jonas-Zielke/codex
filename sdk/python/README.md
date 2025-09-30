# Codex CLI Python bindings

This package provides a thin Python interface around the [Codex CLI](https://github.com/openai/codex)
so that Codex can be invoked from Python code. It shells out to the Codex CLI, which means you can
sign in with your ChatGPT account instead of managing an API key.

## Installation

The package is distributed as part of the Codex repository. To try it locally without publishing you can run:

```bash
pip install -e .
```

from the `sdk/python` directory. This requires that the Codex CLI binary is available on your `PATH`.

## Usage

```python
from codex_cli import CodexCLI

codex = CodexCLI()
status = codex.login_status()
if not status.logged_in:
    codex.login()  # Launches the browser based login flow.

result = codex.run("summarize README.md")
print(result.last_message)
```

The `CodexCLI` helper wraps the `codex exec` command with the new `--experimental-json` output format.
`CodexRunResult` exposes the streamed events emitted by the CLI, the most recent assistant message, token
usage, and any errors reported by Codex.

See the docstrings in `codex_cli.client` for the full list of supported options.
