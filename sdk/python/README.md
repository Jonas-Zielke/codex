# Codex CLI Python bindings

This package provides a Python façade over the [Codex CLI](https://github.com/openai/codex)
so that Codex can be invoked from scripts, notebooks, or back-end services without
managing API keys. All commands shell out to the Codex CLI binary, meaning you can sign
in with the same ChatGPT account that the CLI supports.

## Installation

The package is distributed with the Codex monorepo. To install it locally without
publishing to PyPI, run the following from the `sdk/python` directory:

```bash
pip install -e .
```

Ensure that the `codex` binary is available on your `PATH` before running the examples
below.

## Quickstart

The top-level package exposes helper functions that behave like the CLI commands and
use a shared :class:`codex_cli.CodexCLI` instance under the hood.

```python
from codex_cli import login, login_status, run

# Authenticate using the browser-based flow if needed.
if not login_status().logged_in:
    login()

# Ask Codex to summarise a file.
result = run("summarize README.md")
print(result.last_message)

# Inspect usage and the raw JSON stream emitted by the CLI.
print(result.usage.total_tokens)
print(result.raw_output)
```

If you need customisation (for example, choosing a specific binary or setting
environment variables), construct :class:`codex_cli.CodexCLI` directly.

```python
from codex_cli import CodexCLI

codex = CodexCLI(binary="/opt/codex/bin/codex", env={"CODEX_LOG_LEVEL": "debug"})
codex.login(use_device_code=True)
```

To reuse the same customised client with the module-level helpers, call
``set_default_client`` after creating it.

```python
from codex_cli import CodexCLI, run, set_default_client

client = CodexCLI(check_binary=False)  # e.g. skip the version check in CI
set_default_client(client)

run("list TODOs", require_login=False)
```

## Authentication helpers

```python
from codex_cli import login, login_status, logout, LoginMode

status = login_status()
if not status.logged_in:
    # Capture the login prompt text instead of streaming it directly to stdout/stderr.
    prompt_text = login(stream_output=False, issuer="https://login.example", client_id="abc123")
    print(prompt_text)

if status.mode is LoginMode.CHATGPT:
    print("Logged in with a ChatGPT account")

logout()
```

The ``login`` helper returns the combined stdout/stderr output when ``stream_output``
is ``False``. This is convenient when surfacing the device-code flow inside a GUI or
web form.

## Running prompts

All keyword arguments accepted by :meth:`codex_cli.CodexCLI.run` are available through
the module-level :func:`codex_cli.run` helper. The wrapper automatically parses the
experimental JSON stream and returns a :class:`codex_cli.CodexRunResult` object.

```python
from codex_cli import run

result = run(
    "summarize the plan",
    images=["diagram.png"],
    model="o4-mini",
    config_profile="workspace",
    sandbox_mode="workspace-write",
    config_overrides={"approval": "never"},
    last_message_path="./.codex_last_message.txt",
)

if not result.succeeded:
    raise RuntimeError("Codex reported an error: " + "; ".join(result.errors))

print("Tokens used:", result.usage.total_tokens if result.usage else "unknown")
```

To process streaming events as they arrive, provide an ``on_event`` callback. Each
callback invocation receives the parsed JSON event emitted by ``codex exec``.

```python
from codex_cli import run

def handle_event(event: dict) -> None:
    if event.get("type") == "item.completed":
        print("Received item:", event["item"]["id"])

run("draft release notes", on_event=handle_event)
```

## Returned data structures

The package re-exports several small data classes for ergonomic access to Codex state:

* :class:`codex_cli.LoginStatus` – status of the cached credentials, including
  the detected :class:`codex_cli.LoginMode`.
* :class:`codex_cli.CodexRunResult` – structured representation of a single ``codex exec``
  invocation. Access assistant messages via :attr:`last_message`, retrieve raw
  events through :attr:`events`, or inspect :attr:`usage` for token accounting.
* :class:`codex_cli.Usage` – token usage summary with a convenience
  :attr:`total_tokens` property.

Refer to the docstrings in ``codex_cli.client`` for the full set of parameters and
return types.

## Collaborative workspaces and MCP server

The :mod:`codex_cli.multi_agent` module provides a lightweight abstraction for
coordinating several agents that work on the same local project tree.  Create a
workspace with :class:`codex_cli.MultiAgentWorkspace`, register agents for the
directories they should operate on, and issue prompts through
``workspace.run("alice", "add a README")``.  Each agent keeps track of its
own timestamped history via :class:`codex_cli.AgentSession` while sharing the
underlying Codex client.  Call ``workspace.history()`` to retrieve a
chronological view across all agents.

For multi-agent frameworks that speak the Model Context Protocol, the module
also exposes :class:`codex_cli.LocalProjectMCPServer`.  It adapts a workspace to
a small MCP server that implements ``initialize``, ``tools/list`` and
``tools/call`` so that Codex can be embedded inside other orchestrators without
depending on the Rust implementation.
