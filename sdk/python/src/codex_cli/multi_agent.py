"""Collaborative interfaces for running Codex with multiple local agents.

This module builds on top of :class:`codex_cli.client.CodexCLI` to provide a
simple orchestration layer for scenarios where several agents should be able to
work on the same project simultaneously.  Each agent gets its own working
directory inside the shared project root as well as a history of the prompts it
ran.  The module also exposes a tiny in-process MCP server implementation that
adapts the workspace to the Model Context Protocol so that other multi-agent
systems can interoperate with Codex without having to depend on the Rust
implementation directly.

The design goal here is ergonomics rather than protocol coverage: the MCP
server purposefully implements only a very small subset of the protocol that is
useful for tests and for lightweight experimentation.
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Mapping, MutableMapping, Optional, Union

from .client import CodexCLI, CodexRunResult

JsonDict = MutableMapping[str, object]


@dataclass(frozen=True)
class AgentRun:
    """Record describing a single Codex invocation issued by an agent."""

    prompt: str
    result: CodexRunResult
    timestamp: datetime


class AgentSession:
    """Manage Codex runs for a single agent within a workspace."""

    def __init__(
        self,
        name: str,
        codex: CodexCLI,
        working_directory: Path,
    ) -> None:
        self._name = name
        self._codex = codex
        self._working_directory = working_directory
        self._history: List[AgentRun] = []
        self._lock = threading.Lock()

    # ------------------------------------------------------------------

    @property
    def name(self) -> str:
        """Return the name of the agent."""

        return self._name

    @property
    def working_directory(self) -> Path:
        """Return the agent specific working directory."""

        return self._working_directory

    @property
    def history(self) -> List[AgentRun]:
        """Return the immutable view of the run history."""

        return list(self._history)

    # ------------------------------------------------------------------

    def run(self, prompt: str, **kwargs: object) -> CodexRunResult:
        """Execute a Codex run for this agent.

        Parameters
        ----------
        prompt:
            The user prompt to pass to ``codex exec``.
        **kwargs:
            Additional keyword arguments forwarded to
            :meth:`codex_cli.client.CodexCLI.run`.  The agent automatically
            injects the configured working directory when ``cwd`` is not
            supplied, allowing multiple agents to operate on separate folders.
        """

        forwarded: Dict[str, object] = dict(kwargs)
        forwarded.setdefault("cwd", str(self._working_directory))

        with self._lock:
            result = self._codex.run(prompt, **forwarded)  # type: ignore[arg-type]
            self._history.append(
                AgentRun(
                    prompt=prompt,
                    result=result,
                    timestamp=datetime.now(tz=timezone.utc),
                )
            )
        return result


class MultiAgentWorkspace:
    """Coordinate multiple agents collaborating on the same project tree."""

    def __init__(
        self,
        project_root: Union[str, "Path"],
        *,
        codex: Optional[CodexCLI] = None,
    ) -> None:
        self._project_root = Path(project_root).expanduser().resolve()
        self._codex = codex or CodexCLI()
        self._agents: Dict[str, AgentSession] = {}
        self._lock = threading.RLock()

    # ------------------------------------------------------------------

    @property
    def project_root(self) -> Path:
        """Path to the workspace root shared across agents."""

        return self._project_root

    # ------------------------------------------------------------------

    def register_agent(
        self,
        name: str,
        *,
        relative_path: Optional[Union[str, "Path"]] = None,
        create_missing: bool = True,
    ) -> AgentSession:
        """Register a new agent for the workspace.

        Parameters
        ----------
        name:
            Identifier for the agent.  Names must be unique.
        relative_path:
            Optional path (relative to :attr:`project_root`) that the agent
            should operate from.  When omitted, the root directory is used.
        create_missing:
            When ``True`` (default) the target directory is created on demand.

        Returns
        -------
        AgentSession
            Object that can be used to trigger runs for the agent.
        """

        with self._lock:
            if name in self._agents:
                raise ValueError(f"Agent '{name}' already registered")

            working_directory = self._project_root
            if relative_path is not None:
                candidate = (self._project_root / Path(relative_path)).resolve()
                if self._project_root not in (candidate, *candidate.parents):
                    raise ValueError("Agent working directory must be within the project root")
                working_directory = candidate
            if create_missing:
                working_directory.mkdir(parents=True, exist_ok=True)

            agent = AgentSession(name=name, codex=self._codex, working_directory=working_directory)
            self._agents[name] = agent
            return agent

    # ------------------------------------------------------------------

    def get_agent(self, name: str) -> AgentSession:
        """Return a previously registered agent."""

        with self._lock:
            try:
                return self._agents[name]
            except KeyError as exc:  # pragma: no cover - defensive guard
                raise KeyError(f"Unknown agent '{name}'") from exc

    # ------------------------------------------------------------------

    def agents(self) -> List[AgentSession]:
        """Return the list of registered agents."""

        with self._lock:
            return list(self._agents.values())

    # ------------------------------------------------------------------

    def run(
        self,
        agent_name: str,
        prompt: str,
        **kwargs: object,
    ) -> CodexRunResult:
        """Execute ``codex exec`` for a given agent."""

        agent = self.get_agent(agent_name)
        return agent.run(prompt, **kwargs)

    # ------------------------------------------------------------------

    def history(self, agent_name: Optional[str] = None) -> List[AgentRun]:
        """Return captured history for one agent or the entire workspace."""

        if agent_name is not None:
            return self.get_agent(agent_name).history

        runs: List[AgentRun] = []
        for agent in self.agents():
            runs.extend(agent.history)
        return sorted(runs, key=lambda run: run.timestamp)


class LocalProjectMCPServer:
    """Very small MCP server that exposes a :class:`MultiAgentWorkspace`."""

    TOOL_NAME = "multiagent.run"

    def __init__(self, workspace: MultiAgentWorkspace, *, server_name: str = "codex-local") -> None:
        self._workspace = workspace
        self._server_name = server_name

    # ------------------------------------------------------------------

    def initialize(self) -> JsonDict:
        """Return an ``initialize`` response payload."""

        return {
            "protocolVersion": "2025-06-18",
            "serverInfo": {
                "name": self._server_name,
                "version": "0.1",
            },
            "capabilities": {
                "tools": {"listChanged": False},
            },
        }

    # ------------------------------------------------------------------

    def list_tools(self) -> JsonDict:
        """Return a ``tools/list`` response compatible with MCP."""

        return {
            "tools": [
                {
                    "name": self.TOOL_NAME,
                    "description": "Run a prompt on behalf of a registered agent.",
                    "inputSchema": {
                        "type": "object",
                        "required": ["agent", "prompt"],
                        "properties": {
                            "agent": {"type": "string"},
                            "prompt": {"type": "string"},
                            "config": {"type": "object"},
                        },
                    },
                }
            ]
        }

    # ------------------------------------------------------------------

    def call_tool(self, name: str, arguments: Mapping[str, object]) -> JsonDict:
        """Execute an MCP tool call using the workspace."""

        if name != self.TOOL_NAME:
            raise ValueError(f"Unknown tool '{name}'")

        agent_name = arguments.get("agent")
        prompt = arguments.get("prompt")

        if not isinstance(agent_name, str) or not isinstance(prompt, str):
            raise ValueError("Tool arguments must include 'agent' and 'prompt' strings")

        config_kwargs = arguments.get("config")
        run_kwargs: Dict[str, object] = {}
        if isinstance(config_kwargs, Mapping):
            run_kwargs.update(dict(config_kwargs))

        result = self._workspace.run(agent_name, prompt, **run_kwargs)
        content_text = result.last_message or ""
        payload: JsonDict = {
            "content": [
                {
                    "type": "text",
                    "text": content_text,
                }
            ],
            "isError": bool(result.errors),
        }

        if result.errors:
            payload.setdefault("metadata", {})["errors"] = list(result.errors)

        if result.usage is not None:
            payload.setdefault("metadata", {})["usage"] = {
                "input": result.usage.input_tokens,
                "cached": result.usage.cached_input_tokens,
                "output": result.usage.output_tokens,
            }

        return payload

    # ------------------------------------------------------------------

    def handle_request(self, request: Mapping[str, object]) -> JsonDict:
        """Handle a JSON-RPC request and return the response payload."""

        method = request.get("method")
        request_id = request.get("id")

        try:
            if method == "initialize":
                result = self.initialize()
            elif method == "tools/list":
                result = self.list_tools()
            elif method == "tools/call":
                params = request.get("params")
                if not isinstance(params, Mapping):
                    raise ValueError("tools/call requires params")
                name = params.get("name")
                arguments = params.get("arguments") or {}
                if not isinstance(name, str):
                    raise ValueError("Tool name must be a string")
                if not isinstance(arguments, Mapping):
                    raise ValueError("Tool arguments must be a mapping")
                result = self.call_tool(name, arguments)
            else:
                raise ValueError(f"Unsupported method '{method}'")

            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": result,
            }
        except Exception as exc:  # pragma: no cover - defensive guard for unexpected errors
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {
                    "code": -32000,
                    "message": str(exc),
                },
            }

    # ------------------------------------------------------------------

    def serve_stdio(
        self,
        *,
        input_stream,
        output_stream,
    ) -> None:
        """Serve requests from file-like objects following the MCP framing."""

        for raw_line in input_stream:
            line = raw_line.strip()
            if not line:
                continue
            request = json.loads(line)
            response = self.handle_request(request)
            output_stream.write(json.dumps(response) + "\n")
            output_stream.flush()


__all__ = [
    "AgentRun",
    "AgentSession",
    "MultiAgentWorkspace",
    "LocalProjectMCPServer",
]

