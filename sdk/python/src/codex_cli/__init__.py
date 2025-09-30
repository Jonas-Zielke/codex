"""Python interface for the Codex CLI.

The :class:`~codex_cli.client.CodexCLI` class shells out to the Codex CLI binary and parses
its JSON event stream, which means Codex can be used from Python without an API key.
"""

from .client import CodexCLI, CodexRunResult, LoginMode, LoginStatus, Usage
from .exceptions import CodexCLIError, CodexLoginError, CodexRunError

__all__ = [
    "CodexCLI",
    "CodexRunResult",
    "CodexCLIError",
    "CodexLoginError",
    "CodexRunError",
    "LoginMode",
    "LoginStatus",
    "Usage",
]
