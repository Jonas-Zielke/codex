# Movement Anti-Cheat Module

This example Spigot/Paper module demonstrates how to capture raw movement data every tick and validate it against simplified Minecraft physics. The code lives under `com.example.anticheat.movement` and is intended to be embedded into a larger anti-cheat plugin.

## How it works

* `MovementListener` hooks `PlayerMoveEvent` at `MONITOR` priority to capture the player's raw velocity, position delta, and on-ground state each tick.
* Samples are compared via `MovementMonitor`, which applies gravity, drag, and ground friction heuristics to predict expected motion. Deviations above configurable tolerances are turned into `MovementViolation` instances.
* A rolling `MovementViolationTracker` keeps the most recent violations within a decay window. When the buffer exceeds thresholds defined in `movement.yml`, staff notifications or automatic set-backs are triggered unless the player holds the bypass permission.

## Configuration (`movement.yml`)

| Path | Description |
| ---- | ----------- |
| `physics.gravity` | Downward acceleration applied while airborne. Increase slightly for lower tick-rate servers where gravity feels weaker. |
| `physics.air-drag` | Drag multiplier applied to horizontal velocity while airborne. Raise this if players with higher latency are being flagged mid-air. |
| `physics.ground-friction` | Friction applied to horizontal velocity on-ground. Increase when players are frequently sprint jumping on low-TPS servers. |
| `thresholds.horizontal-percent` | Allowed percentage deviation from expected horizontal motion. Add ~5% per 50 ms average latency if you see false positives. |
| `thresholds.vertical-percent` | Allowed percentage deviation from expected vertical motion. Increase when jump pads or trident boosts are in use. |
| `thresholds.collision-*` | Extra allowance granted when the player collides with solid blocks (stairs, slabs, ladders). |
| `violations.decay-seconds` | How quickly past violations expire. Lower values keep the buffer responsive on high latency networks. |
| `violations.max-buffer` | Maximum number of stored violations per player. |
| `violations.triggers.notify.threshold` | Number of active violations before notifying staff (`anticheat.notify`). |
| `violations.triggers.set-back.threshold` | Number of active violations before teleporting the player to their last safe location. |
| `bypass-permission` | Root permission required to ignore checks (temporary bypass nodes are supported with `bypass-permission.*`). |

### Tuning tips

* **Match server tick-rate** – When TPS drops below 20, gravity and drag effectively weaken. Increase `physics.gravity` by `(20 − average TPS) × 0.002` and reduce `thresholds.horizontal-percent` to keep legitimate players from being flagged.
* **Account for latency** – Each additional 50 ms of ping generally warrants +3–5% tolerance on both horizontal and vertical thresholds. Consider raising `violations.decay-seconds` for regions with >150 ms latency to avoid bursty false positives.
* **Set-back thresholds** – Start with `notify.threshold = 3` and `set-back.threshold = 5` to gather telemetry. Once confident, lower the set-back threshold gradually until false positives disappear.
* **Temporary bypass** – Grant `anticheat.movement.bypass.temp` (or any sub-node of the bypass permission) for players needing short-term exemptions. These nodes are detected automatically via `hasTemporaryBypass`.

## Extending

The listener intentionally exposes clean extension points:

* Add new triggers by inserting entries under `violations.triggers` and branching on `ActionThreshold#type` in `handleActions`.
* Feed the rolling violation buffer into a metrics system or logging pipeline for auditing.
* Combine with other movement heuristics (elytra, swimming) by expanding `MovementMonitor#evaluate`.
