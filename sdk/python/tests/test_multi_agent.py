import json
import pathlib
import threading
import sys
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Dict

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from codex_cli import (  # noqa: E402  pylint: disable=wrong-import-position
    AgentRun,
    LocalProjectMCPServer,
    MultiAgentWorkspace,
)
from codex_cli import CodexRunResult, Usage  # noqa: E402  pylint: disable=wrong-import-position


class ThreadSafeDummyClient:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.calls: Dict[str, Dict[str, object]] = {}

    def run(self, prompt: str, **kwargs: object) -> CodexRunResult:
        cwd = kwargs.get("cwd")
        with self.lock:
            self.calls[prompt] = {"cwd": cwd}

        return CodexRunResult(
            events=[],
            assistant_messages=[f"completed:{prompt}"],
            reasoning=[],
            usage=Usage(input_tokens=1, cached_input_tokens=0, output_tokens=1),
            errors=[],
            raw_output=json.dumps({"prompt": prompt}),
            stderr="",
            thread_id=f"thread-{prompt}",
        )


class MultiAgentWorkspaceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.client = ThreadSafeDummyClient()
        self.workspace = MultiAgentWorkspace(self.root, codex=self.client)

    def test_agents_use_separate_working_directories(self) -> None:
        self.workspace.register_agent("alice")
        self.workspace.register_agent("bob", relative_path="team/bob")

        with ThreadPoolExecutor(max_workers=2) as pool:
            fut1 = pool.submit(self.workspace.run, "alice", "hello")
            fut2 = pool.submit(self.workspace.run, "bob", "world")

        result_a = fut1.result()
        result_b = fut2.result()

        self.assertEqual(
            Path(self.client.calls["hello"]["cwd"]).resolve(),
            self.root.resolve(),
        )
        self.assertEqual(
            Path(self.client.calls["world"]["cwd"]).resolve(),
            (self.root / "team" / "bob").resolve(),
        )

        alice_history = self.workspace.history("alice")
        self.assertEqual(len(alice_history), 1)
        self.assertIsInstance(alice_history[0], AgentRun)
        self.assertEqual(alice_history[0].prompt, "hello")
        self.assertIs(alice_history[0].result, result_a)
        self.assertIsInstance(alice_history[0].timestamp, datetime)

        bob_history = self.workspace.get_agent("bob").history
        self.assertEqual(len(bob_history), 1)
        self.assertEqual(bob_history[0].prompt, "world")
        self.assertIs(bob_history[0].result, result_b)

    def test_register_agent_rejects_duplicates_and_outside_paths(self) -> None:
        self.workspace.register_agent("solo")
        with self.assertRaisesRegex(ValueError, "already registered"):
            self.workspace.register_agent("solo")

        with self.assertRaisesRegex(ValueError, "within the project root"):
            self.workspace.register_agent("eve", relative_path="../outside")

    def test_history_returns_runs_in_chronological_order(self) -> None:
        alice = self.workspace.register_agent("alice")
        bob = self.workspace.register_agent("bob")

        alice.run("first")
        time.sleep(0.01)
        bob.run("second")

        combined = self.workspace.history()
        self.assertEqual([run.prompt for run in combined], ["first", "second"])


class LocalProjectMCPServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.client = ThreadSafeDummyClient()
        self.workspace = MultiAgentWorkspace(self.root, codex=self.client)
        self.workspace.register_agent("alice")
        self.server = LocalProjectMCPServer(self.workspace)

    def test_initialize_and_list_tools(self) -> None:
        init = self.server.handle_request({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        self.assertIn("result", init)
        self.assertEqual(init["result"]["serverInfo"]["name"], "codex-local")

        tools = self.server.handle_request({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        tool_names = [tool["name"] for tool in tools["result"]["tools"]]
        self.assertIn(LocalProjectMCPServer.TOOL_NAME, tool_names)

    def test_tool_call_routes_to_workspace(self) -> None:
        request = {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": LocalProjectMCPServer.TOOL_NAME,
                "arguments": {"agent": "alice", "prompt": "refactor"},
            },
        }

        response = self.server.handle_request(request)
        self.assertNotIn("error", response)
        self.assertEqual(response["result"]["content"][0]["text"], "completed:refactor")

    def test_tool_call_with_unknown_agent_returns_error(self) -> None:
        request = {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {
                "name": LocalProjectMCPServer.TOOL_NAME,
                "arguments": {"agent": "bob", "prompt": "lint"},
            },
        }

        response = self.server.handle_request(request)
        self.assertIn("error", response)
        self.assertIn("Unknown agent", response["error"]["message"])


if __name__ == "__main__":
    unittest.main()
