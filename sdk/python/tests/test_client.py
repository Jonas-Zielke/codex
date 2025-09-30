import os
import pathlib
import sys
import unittest
from typing import Callable, Dict, Iterable, Mapping, Optional, Sequence, Union

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from codex_cli import (
    CodexRunResult,
    LoginMode,
    LoginStatus,
    Usage,
    login,
    login_status,
    logout,
    reset_default_client,
    run,
    set_default_client,
)
from codex_cli.client import _ExperimentalJsonAggregator, _format_config_overrides


class ExperimentalJsonAggregatorTests(unittest.TestCase):
    def test_collects_assistant_messages_reasoning_and_usage(self) -> None:
        aggregator = _ExperimentalJsonAggregator()
        aggregator.add_line('{"type":"thread.started","thread_id":"abc"}')
        aggregator.add_line(
            '{"type":"item.completed","item":{"id":"1","details":{"item_type":"assistant_message","text":"Hello"}}}'
        )
        aggregator.add_line(
            '{"type":"item.completed","item":{"id":"2","details":{"item_type":"reasoning","text":"Thinking"}}}'
        )
        aggregator.add_line(
            '{"type":"turn.completed","usage":{"input_tokens":7,"cached_input_tokens":2,"output_tokens":5}}'
        )

        result = aggregator.as_result(stderr="")
        self.assertIsInstance(result, CodexRunResult)
        self.assertEqual(result.thread_id, "abc")
        self.assertEqual(result.assistant_messages, ["Hello"])
        self.assertEqual(result.reasoning, ["Thinking"])
        self.assertIsInstance(result.usage, Usage)
        self.assertEqual(result.usage.input_tokens, 7)
        self.assertEqual(result.usage.cached_input_tokens, 2)
        self.assertEqual(result.usage.output_tokens, 5)
        self.assertEqual(result.usage.total_tokens, 14)
        self.assertTrue(result.succeeded)
        self.assertEqual(result.last_message, "Hello")

    def test_records_errors_from_turn_failed_and_error_events(self) -> None:
        aggregator = _ExperimentalJsonAggregator()
        aggregator.add_line('{"type":"turn.failed","error":{"message":"failure"}}')
        aggregator.add_line('{"type":"error","message":"secondary"}')
        result = aggregator.as_result(stderr="boom")

        self.assertFalse(result.succeeded)
        self.assertEqual(result.errors, ["failure", "secondary"])
        self.assertEqual(result.stderr, "boom")


class FormatConfigOverridesTests(unittest.TestCase):
    def test_handles_mapping_input(self) -> None:
        overrides = {"model": "o4-mini", "profile": "default"}
        formatted = _format_config_overrides(overrides)
        self.assertEqual(formatted, ["-c", "model=o4-mini", "-c", "profile=default"])

    def test_handles_iterable_input(self) -> None:
        formatted = _format_config_overrides(["sandbox=workspace-write", "approval=never"])
        self.assertEqual(
            formatted,
            ["-c", "sandbox=workspace-write", "-c", "approval=never"],
        )


class DefaultClientHelpersTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = DummyClient()
        set_default_client(self.client)

    def tearDown(self) -> None:
        reset_default_client()

    def test_login_status_uses_configured_client(self) -> None:
        status = login_status(env={"A": "B"})
        self.assertEqual(status.message, "Logged in")
        self.assertEqual(self.client.login_status_kwargs, {"env": {"A": "B"}})

    def test_login_returns_captured_output(self) -> None:
        output = login(stream_output=False, env={"X": "Y"})
        self.assertEqual(output, "device code")
        self.assertEqual(
            self.client.login_kwargs,
            {
                "mode": LoginMode.CHATGPT,
                "api_key": None,
                "use_device_code": False,
                "issuer": None,
                "client_id": None,
                "stream_output": False,
                "env": {"X": "Y"},
            },
        )

    def test_logout_forwards_call(self) -> None:
        logout(env={"LOG": "1"})
        self.assertEqual(self.client.logout_kwargs, {"env": {"LOG": "1"}})

    def test_run_forwards_all_arguments(self) -> None:
        result = run(
            "hello",
            images=["img.png"],
            model="o4",
            config_profile="workspace",
            full_auto=True,
            dangerously_bypass_approvals=True,
            sandbox_mode="workspace-write",
            cwd="/tmp",
            include_plan_tool=True,
            config_overrides={"approval": "never"},
            last_message_path="last.txt",
            output_schema="schema.json",
            resume_session_id="abc",
            resume_last=False,
            require_login=False,
            on_event=self.client.record_event,
            env={"ENV": "1"},
            oss=True,
            skip_git_repo_check=True,
        )

        self.assertIs(result, self.client.run_result)
        self.assertEqual(
            self.client.run_kwargs,
            {
                "prompt": "hello",
                "images": ["img.png"],
                "model": "o4",
                "config_profile": "workspace",
                "full_auto": True,
                "dangerously_bypass_approvals": True,
                "sandbox_mode": "workspace-write",
                "cwd": "/tmp",
                "include_plan_tool": True,
                "config_overrides": {"approval": "never"},
                "last_message_path": "last.txt",
                "output_schema": "schema.json",
                "resume_session_id": "abc",
                "resume_last": False,
                "require_login": False,
                "on_event": self.client.record_event,
                "env": {"ENV": "1"},
                "oss": True,
                "skip_git_repo_check": True,
            },
        )


class DummyClient:
    def __init__(self) -> None:
        self.login_status_kwargs: Dict[str, object] = {}
        self.login_kwargs: Dict[str, object] = {}
        self.logout_kwargs: Dict[str, object] = {}
        self.run_kwargs: Dict[str, object] = {}
        self.run_result = CodexRunResult(
            events=[],
            assistant_messages=["Hi"],
            reasoning=[],
            usage=Usage(input_tokens=1, cached_input_tokens=0, output_tokens=1),
            errors=[],
            raw_output="{}",
            stderr="",
            thread_id="t-1",
        )

    def login_status(self, *, env: Optional[Mapping[str, str]] = None) -> LoginStatus:
        self.login_status_kwargs = {"env": env}
        return LoginStatus(True, LoginMode.CHATGPT, "Logged in")

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
    ) -> str:
        self.login_kwargs = {
            "mode": mode,
            "api_key": api_key,
            "use_device_code": use_device_code,
            "issuer": issuer,
            "client_id": client_id,
            "stream_output": stream_output,
            "env": env,
        }
        return "device code"

    def logout(self, *, env: Optional[Mapping[str, str]] = None) -> None:
        self.logout_kwargs = {"env": env}

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
        self.run_kwargs = {
            "prompt": prompt,
            "images": images,
            "model": model,
            "config_profile": config_profile,
            "full_auto": full_auto,
            "dangerously_bypass_approvals": dangerously_bypass_approvals,
            "sandbox_mode": sandbox_mode,
            "cwd": cwd,
            "include_plan_tool": include_plan_tool,
            "config_overrides": config_overrides,
            "last_message_path": last_message_path,
            "output_schema": output_schema,
            "resume_session_id": resume_session_id,
            "resume_last": resume_last,
            "require_login": require_login,
            "on_event": on_event,
            "env": env,
            "oss": oss,
            "skip_git_repo_check": skip_git_repo_check,
        }
        if on_event:
            on_event({"type": "noop"})
        return self.run_result

    def record_event(self, event: Dict[str, object]) -> None:
        pass

if __name__ == "__main__":
    unittest.main()
