"""Custom exceptions raised by :mod:`codex_cli`."""

from __future__ import annotations


class CodexCLIError(RuntimeError):
    """Base exception for all Codex CLI integration errors."""


class CodexLoginError(CodexCLIError):
    """Raised when the Codex CLI login flow fails or authentication is missing."""


class CodexRunError(CodexCLIError):
    """Raised when ``codex exec`` exits unsuccessfully."""

    def __init__(self, message: str, *, stderr: str = "", stdout: str = "") -> None:
        super().__init__(message)
        self.stderr = stderr
        self.stdout = stdout
