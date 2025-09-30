import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from codex_cli.client import (
    CodexRunResult,
    Usage,
    _ExperimentalJsonAggregator,
    _format_config_overrides,
)


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


if __name__ == "__main__":
    unittest.main()
