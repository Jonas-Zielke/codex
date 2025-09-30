"""Python interface for the Codex CLI.

The :class:`~codex_cli.client.CodexCLI` class shells out to the Codex CLI binary and parses
its JSON event stream, which means Codex can be used from Python without an API key.


In addition to re-exporting :class:`CodexCLI` and related data classes, this module exposes
module-level convenience helpers that mirror the CLI commands. They use a shared
``CodexCLI`` instance that can be customised via :func:`set_default_client`.
"""

from __future__ import annotations

from typing import Callable, Dict, Iterable, Mapping, Optional, Sequence, Union

from .client import CodexCLI, CodexRunResult, LoginMode, LoginStatus, Usage
from .multi_agent import AgentRun, AgentSession, LocalProjectMCPServer, MultiAgentWorkspace
from .exceptions import CodexCLIError, CodexLoginError, CodexRunError

_default_client: Optional[CodexCLI] = None


def get_client() -> CodexCLI:
    """Return the shared :class:`CodexCLI` instance used by helper functions."""

    global _default_client
    if _default_client is None:
        _default_client = CodexCLI()
    return _default_client


def set_default_client(client: CodexCLI) -> CodexCLI:
    """Set the shared :class:`CodexCLI` instance used by helper functions."""

    global _default_client
    _default_client = client
    return client


def reset_default_client() -> None:
    """Clear the shared :class:`CodexCLI` instance used by helper functions."""

    global _default_client
    _default_client = None


def login_status(*, env: Optional[Mapping[str, str]] = None) -> LoginStatus:
    """Return the current authentication status using the shared client."""

    return get_client().login_status(env=env)


def login(
    *,
    mode: LoginMode = LoginMode.CHATGPT,
    api_key: Optional[str] = None,
    use_device_code: bool = False,
    issuer: Optional[str] = None,
    client_id: Optional[str] = None,
    stream_output: bool = True,
    env: Optional[Mapping[str, str]] = None,
) -> Optional[str]:
    """Authenticate with Codex using the shared client."""

    return get_client().login(
        mode=mode,
        api_key=api_key,
        use_device_code=use_device_code,
        issuer=issuer,
        client_id=client_id,
        stream_output=stream_output,
        env=env,
    )


def logout(*, env: Optional[Mapping[str, str]] = None) -> None:
    """Remove stored Codex credentials using the shared client."""

    return get_client().logout(env=env)


def run(
    prompt: str,
    *,
    images: Optional[Sequence[Union[str, "os.PathLike[str]"]]] = None,
    model: Optional[str] = None,
    config_profile: Optional[str] = None,
    full_auto: bool = False,
    dangerously_bypass_approvals: bool = False,
    sandbox_mode: Optional[str] = None,
    cwd: Optional[Union[str, "os.PathLike[str]"]] = None,
    include_plan_tool: bool = False,
    config_overrides: Optional[Union[Mapping[str, object], Iterable[str]]] = None,
    last_message_path: Optional[Union[str, "os.PathLike[str]"]] = None,
    output_schema: Optional[Union[str, "os.PathLike[str]"]] = None,
    resume_session_id: Optional[str] = None,
    resume_last: bool = False,
    require_login: bool = True,
    on_event: Optional[Callable[[Dict[str, object]], None]] = None,
    env: Optional[Mapping[str, str]] = None,
    oss: bool = False,
    skip_git_repo_check: bool = False,
) -> CodexRunResult:
    """Run ``codex exec`` using the shared client."""

    return get_client().run(
        prompt,
        images=images,
        model=model,
        config_profile=config_profile,
        full_auto=full_auto,
        dangerously_bypass_approvals=dangerously_bypass_approvals,
        sandbox_mode=sandbox_mode,
        cwd=cwd,
        include_plan_tool=include_plan_tool,
        config_overrides=config_overrides,
        last_message_path=last_message_path,
        output_schema=output_schema,
        resume_session_id=resume_session_id,
        resume_last=resume_last,
        require_login=require_login,
        on_event=on_event,
        env=env,
        oss=oss,
        skip_git_repo_check=skip_git_repo_check,
    )


__all__ = [
    "CodexCLI",
    "CodexRunResult",
    "CodexCLIError",
    "CodexLoginError",
    "CodexRunError",
    "LoginMode",
    "LoginStatus",
    "Usage",
    "AgentRun",
    "AgentSession",
    "MultiAgentWorkspace",
    "LocalProjectMCPServer",
    "get_client",
    "set_default_client",
    "reset_default_client",
    "login_status",
    "login",
    "logout",
    "run",
]
