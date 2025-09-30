"""High level Python interface for invoking the Codex CLI."""

from __future__ import annotations

import json
import os
import subprocess
import threading
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import IO, Callable, Dict, Iterable, List, Mapping, Optional, Sequence, Union

from .exceptions import CodexCLIError, CodexLoginError, CodexRunError

# Public data containers -----------------------------------------------------------------------


class LoginMode(str, Enum):
    """Authentication mode used by the Codex CLI."""

    CHATGPT = "chatgpt"
    API_KEY = "api_key"
    UNKNOWN = "unknown"
    NONE = "none"


@dataclass(frozen=True)
class LoginStatus:
    """Result of ``codex login status``."""

    logged_in: bool
    mode: LoginMode
    message: str


@dataclass(frozen=True)
class Usage:
    """Token usage summary produced at the end of a Codex run."""

    input_tokens: int
    cached_input_tokens: int
    output_tokens: int

    @property
    def total_tokens(self) -> int:
        """Return the total tokens consumed (cached + input + output)."""

        return self.input_tokens + self.cached_input_tokens + self.output_tokens


@dataclass(frozen=True)
class CodexRunResult:
    """Structured result returned from :meth:`CodexCLI.run`."""

    events: List[Dict[str, object]]
    assistant_messages: List[str]
    reasoning: List[str]
    usage: Optional[Usage]
    errors: List[str]
    raw_output: str
    stderr: str
    thread_id: Optional[str]

    @property
    def last_message(self) -> Optional[str]:
        """Return the final assistant message, if Codex produced one."""

        return self.assistant_messages[-1] if self.assistant_messages else None

    @property
    def succeeded(self) -> bool:
        """True when Codex finished the turn without emitting an error."""

        return not self.errors


# Internal helpers -----------------------------------------------------------------------------


@dataclass
class _ExperimentalJsonAggregator:
    """Collects experimental JSON events emitted by ``codex exec``."""

    raw_lines: List[str] = field(default_factory=list)
    events: List[Dict[str, object]] = field(default_factory=list)
    assistant_messages: List[str] = field(default_factory=list)
    reasoning: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    thread_id: Optional[str] = None
    usage: Optional[Usage] = None

    def add_line(self, raw_line: str) -> None:
        line = raw_line.strip()
        if not line:
            return

        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:  # pragma: no cover - surfaced as CodexRunError
            raise CodexRunError(
                "Failed to decode JSON event emitted by codex exec", stderr="", stdout=line
            ) from exc

        self.raw_lines.append(line)
        self.events.append(event)

        event_type = event.get("type")
        if event_type == "thread.started":
            thread_id = event.get("thread_id")
            if isinstance(thread_id, str):
                self.thread_id = thread_id
        elif event_type == "item.completed":
            item = event.get("item")
            if isinstance(item, dict):
                details = item.get("details")
                if isinstance(details, dict):
                    item_type = details.get("item_type")
                    text = details.get("text")
                    if item_type == "assistant_message" and isinstance(text, str):
                        self.assistant_messages.append(text)
                    elif item_type == "reasoning" and isinstance(text, str):
                        self.reasoning.append(text)
        elif event_type == "turn.completed":
            usage = event.get("usage")
            if isinstance(usage, dict):
                self.usage = Usage(
                    input_tokens=int(usage.get("input_tokens", 0)),
                    cached_input_tokens=int(usage.get("cached_input_tokens", 0)),
                    output_tokens=int(usage.get("output_tokens", 0)),
                )
        elif event_type == "turn.failed":
            error = event.get("error")
            if isinstance(error, dict):
                message = error.get("message")
                if isinstance(message, str):
                    self.errors.append(message)
        elif event_type == "error":
            message = event.get("message")
            if isinstance(message, str):
                self.errors.append(message)

    def as_result(self, stderr: str) -> CodexRunResult:
        return CodexRunResult(
            events=self.events,
            assistant_messages=self.assistant_messages,
            reasoning=self.reasoning,
            usage=self.usage,
            errors=self.errors,
            raw_output="\n".join(self.raw_lines),
            stderr=stderr,
            thread_id=self.thread_id,
        )


def _build_cli_env(base_env: Mapping[str, str], extra_env: Optional[Mapping[str, str]]) -> Dict[str, str]:
    env: Dict[str, str] = dict(base_env)
    if extra_env:
        env.update(extra_env)
    return env


def _format_config_overrides(overrides: Optional[Union[Mapping[str, object], Iterable[str]]]) -> List[str]:
    if overrides is None:
        return []

    formatted: List[str] = []
    if isinstance(overrides, Mapping):
        for key, value in overrides.items():
            formatted.extend(["-c", f"{key}={value}"])
        return formatted

    for entry in overrides:
        formatted.extend(["-c", str(entry)])
    return formatted


def _drain_stream(stream: Optional[IO[str]], bucket: List[str]) -> None:
    if stream is None:
        return
    for line in stream:
        bucket.append(line)


# Main interface ------------------------------------------------------------------------------


class CodexCLI:
    """Wrapper around the Codex CLI executable.

    Parameters
    ----------
    binary:
        Name or path of the Codex CLI executable. Defaults to ``"codex"``.
    env:
        Extra environment variables to pass to the CLI.
    codex_home:
        Optional override for the ``CODEX_HOME`` directory.
    check_binary:
        When ``True`` (default), ``codex --version`` is executed during
        initialisation to surface missing binaries early.
    """

    def __init__(
        self,
        binary: str = "codex",
        *,
        env: Optional[Mapping[str, str]] = None,
        codex_home: Optional[Union[str, os.PathLike[str]]] = None,
        check_binary: bool = True,
    ) -> None:
        base_env: Dict[str, str] = dict(os.environ)
        if env:
            base_env.update(env)
        if codex_home is not None:
            base_env["CODEX_HOME"] = str(Path(codex_home).expanduser())

        self._binary = binary
        self._base_env = base_env

        if check_binary:
            self._ensure_binary()

    # ------------------------------------------------------------------

    def _ensure_binary(self) -> None:
        try:
            subprocess.run(
                [self._binary, "--version"],
                env=_build_cli_env(self._base_env, None),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                check=True,
            )
        except FileNotFoundError as exc:  # pragma: no cover - exercised in integration usage
            raise CodexCLIError(
                f"Codex CLI binary '{self._binary}' was not found on PATH"
            ) from exc
        except subprocess.CalledProcessError as exc:  # pragma: no cover - depends on binary state
            stderr = exc.stderr.decode() if isinstance(exc.stderr, bytes) else str(exc.stderr)
            raise CodexCLIError(
                f"Failed to invoke '{self._binary} --version': {stderr.strip()}"
            ) from exc

    # ------------------------------------------------------------------

    def login_status(self, *, env: Optional[Mapping[str, str]] = None) -> LoginStatus:
        """Return the current authentication status.

        The CLI prints status information to stderr and exits with a non-zero code when
        the user is not logged in.
        """

        proc = subprocess.run(
            [self._binary, "login", "status"],
            env=_build_cli_env(self._base_env, env),
            capture_output=True,
            text=True,
            check=False,
        )

        message = (proc.stderr or proc.stdout or "").strip()

        if proc.returncode == 0:
            if "ChatGPT" in message:
                mode = LoginMode.CHATGPT
            elif "API key" in message:
                mode = LoginMode.API_KEY
            else:
                mode = LoginMode.UNKNOWN
            return LoginStatus(True, mode, message)

        return LoginStatus(False, LoginMode.NONE, message or "Not logged in")

    # ------------------------------------------------------------------

    def login(
        self,
        *,
        mode: LoginMode = LoginMode.CHATGPT,
        api_key: Optional[str] = None,
        use_device_code: bool = False,
        issuer: Optional[str] = None,
        client_id: Optional[str] = None,
        stream_output: bool = True,
        env: Optional[Mapping[str, str]] = None,
    ) -> Optional[str]:
        """Authenticate with Codex.

        For ChatGPT based login, the CLI starts a local web server and prints a URL
        to the terminal. Set ``stream_output=False`` to capture the CLI output instead of
        streaming it directly to stdout/stderr. When ``stream_output`` is ``False`` the
        decoded combined stdout/stderr text is returned on success.
        """

        args = [self._binary, "login"]

        if mode == LoginMode.API_KEY:
            if not api_key:
                raise ValueError("An API key must be provided when mode=LoginMode.API_KEY")
            args.extend(["--api-key", api_key])
        else:
            if use_device_code:
                args.append("--experimental_use-device-code")
            if issuer:
                args.extend(["--experimental_issuer", issuer])
            if client_id:
                args.extend(["--experimental_client-id", client_id])

        cli_env = _build_cli_env(self._base_env, env)

        captured_output: Optional[str] = None
        if stream_output:
            proc = subprocess.run(args, env=cli_env, check=False)
        else:
            proc = subprocess.run(
                args,
                env=cli_env,
                capture_output=True,
                text=True,
                check=False,
            )
            captured_output = "".join(filter(None, [proc.stdout, proc.stderr]))

        if proc.returncode != 0:
            message = captured_output.strip() if captured_output else None
            if not message and stream_output:
                message = "codex login exited with a non-zero status"
            raise CodexLoginError(
                message or f"codex login exited with status {proc.returncode}",
            )

        if not stream_output:
            return captured_output or ""

        return None

    # ------------------------------------------------------------------

    def logout(self, *, env: Optional[Mapping[str, str]] = None) -> None:
        """Remove stored Codex credentials."""

        proc = subprocess.run(
            [self._binary, "logout"],
            env=_build_cli_env(self._base_env, env),
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            message = (proc.stderr or proc.stdout or "Failed to log out").strip()
            raise CodexLoginError(message)

    # ------------------------------------------------------------------

    def run(
        self,
        prompt: str,
        *,
        images: Optional[Sequence[Union[str, os.PathLike[str]]]] = None,
        model: Optional[str] = None,
        config_profile: Optional[str] = None,
        full_auto: bool = False,
        dangerously_bypass_approvals: bool = False,
        sandbox_mode: Optional[str] = None,
        cwd: Optional[Union[str, os.PathLike[str]]] = None,
        include_plan_tool: bool = False,
        config_overrides: Optional[Union[Mapping[str, object], Iterable[str]]] = None,
        last_message_path: Optional[Union[str, os.PathLike[str]]] = None,
        output_schema: Optional[Union[str, os.PathLike[str]]] = None,
        resume_session_id: Optional[str] = None,
        resume_last: bool = False,
        require_login: bool = True,
        on_event: Optional[Callable[[Dict[str, object]], None]] = None,
        env: Optional[Mapping[str, str]] = None,
        oss: bool = False,
        skip_git_repo_check: bool = False,
    ) -> CodexRunResult:
        """Run ``codex exec`` using the experimental JSON output format.

        Parameters mirror common CLI flags. The method raises :class:`CodexRunError`
        when the CLI exits with a non-zero status code.
        """

        if require_login:
            status = self.login_status(env=env)
            if not status.logged_in:
                raise CodexLoginError(
                    "Codex is not authenticated. Call CodexCLI.login() before invoking run()."
                )

        args: List[str] = [self._binary, "exec", "--experimental-json"]

        if images:
            for image in images:
                args.extend(["--image", str(Path(image))])
        if model:
            args.extend(["--model", model])
        if oss:
            args.append("--oss")
        if config_profile:
            args.extend(["--profile", config_profile])
        if full_auto:
            args.append("--full-auto")
        if dangerously_bypass_approvals:
            args.append("--dangerously-bypass-approvals-and-sandbox")
        if sandbox_mode:
            args.extend(["--sandbox", sandbox_mode])
        if cwd:
            args.extend(["--cd", str(Path(cwd))])
        if skip_git_repo_check:
            args.append("--skip-git-repo-check")
        if include_plan_tool:
            args.append("--include-plan-tool")
        if last_message_path:
            args.extend(["--output-last-message", str(Path(last_message_path))])
        if output_schema:
            args.extend(["--output-schema", str(Path(output_schema))])

        args.extend(_format_config_overrides(config_overrides))

        if resume_session_id or resume_last:
            args.append("resume")
            if resume_last:
                args.append("--last")
            elif resume_session_id:
                args.append(resume_session_id)
            args.append(prompt)
        else:
            args.append(prompt)

        cli_env = _build_cli_env(self._base_env, env)

        process = subprocess.Popen(
            args,
            env=cli_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

        stderr_lines: List[str] = []
        stderr_thread = threading.Thread(
            target=_drain_stream,
            args=(process.stderr, stderr_lines),
            daemon=True,
        )
        stderr_thread.start()

        aggregator = _ExperimentalJsonAggregator()
        try:
            assert process.stdout is not None  # for the type checker
            for line in process.stdout:
                aggregator.add_line(line)
                if on_event and aggregator.events:
                    on_event(aggregator.events[-1])
        finally:
            if process.stdout is not None:
                process.stdout.close()

        return_code = process.wait()
        stderr_thread.join()
        stderr = "".join(stderr_lines)

        result = aggregator.as_result(stderr)
        if return_code != 0:
            raise CodexRunError(
                f"codex exec exited with status {return_code}",
                stderr=stderr,
                stdout=result.raw_output,
            )

        return result


__all__ = [
    "CodexCLI",
    "CodexRunResult",
    "LoginMode",
    "LoginStatus",
    "Usage",
]
