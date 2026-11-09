# Residual-debt decision (8/10 bar)

Decision: the remaining empty catches and long methods are **accepted residual
debt for the 8/10 target**. They are tracked here so a later 9/10 push has a
concrete work list.

## Accepted debt

| Item | Count | Why accepted |
|---|---|---|
| Empty catch blocks (`src/main/java`) | 98 of 250 (39.2%); 113 including comment-only | Concentrated in optional 1.7.10 integration/reflection probes (HBM/OC/Xaero/Forge). Swallowing is often the intended behaviour; the hot paths are covered by 599 tests. |
| Methods > 80 lines | body-based 14 main + 2 test; span-based 16 main + 2 test; largest `NavigateAction.tick` 164 | Cohesive orchestration/scan loops in otherwise small files (max file 391). No class is a god object; the 400-line gate and package gate are enforced. |

## Not accepted as "remaining"

- Policy constants, schema prose, tool timeouts, skill caps/defaults and
  navigation/scan tuning are now single-owned (`ScanLimits`, `InventoryLimits`,
  `NavigationLimits`, `NavigationTuning`, `VisualLimits`, `WorldBounds`,
  `SkillLimits`, `ToolTimeouts`, `LIMITS`, `SKILL_LIMITS`) and guarded by
  agreement tests.
- Monolithic files: none over 400 lines in `src/main/java` or `src/test/java`.

## If the bar moves to 9/10

1. Split `NavigateAction.tick`, `ContainerSearchService.searchChestsForItem`,
   `SiteSurvey.survey`, `RoomScanner.scanRoom3D`.
2. Replace silent `catch (Throwable ignored)` on core paths with a documented
   `BestEffort`/debug-log helper, keeping reflection probes quiet.
3. Add the last inline per-tool timeouts to `ToolTimeouts` tiers (already
   centralized) and pin any remaining skill-local task caps.
