//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// LED rainbow. params: { steps, delay_ms }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var steps = clamp(cfg.steps || 36, SKILL_LIMITS.rainbowStepsMin, SKILL_LIMITS.rainbowStepsMax);
var delay = clamp(cfg.delay_ms || 120, 0, SKILL_LIMITS.rainbowDelayMax);
for (var i = 0; i < steps; i++) {
    drone.setLed({color: hsvToRgb((i * 360 / steps) % 360, 1, 1)});
    if (delay > 0) sleep(delay);
}
return {ok: true, steps: steps};
