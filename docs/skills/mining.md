# Skill: Mine Blocks

Mining is a composition skill, not a Java task. There is no `drone.mine()`:
inspect, move to a safe standoff, break one block, repeat.

```js
async function mineBlocks(filter, radius = 24, limit = 8) {
  const found = await drone.inspect('blocks', { filter, radius });
  const blocks = (found.blocks || []).slice(0, limit);
  const mined = [];

  for (const block of blocks) {
    const moved = await drone.moveTo({
      x: block.x, y: block.y, z: block.z,
      route: 'auto',
      standoffOf: { x: block.x, y: block.y, z: block.z }
    });
    if (!moved || moved.arrived !== true) continue;

    const broke = await drone.breakBlock(block.x, block.y, block.z);
    if (broke && broke.ok !== false) mined.push(block);
  }

  return {
    mined,
    count: mined.length,
    remaining: Math.max(0, (found.count || 0) - mined.length)
  };
}
```

Use it for a specific ore:

```js
return await mineBlocks('iron_ore', 24, 8);
```

Save it as a reusable skill once verified:

```js
await drone.skills('save', {
  name: 'mine_blocks',
  description: 'Inspect, stand off, and break a bounded set of matching blocks.',
  code: '...the function above...',
  tags: 'mining,ore,break'
});
```

Notes:

- `inspect('blocks')` returns a bounded list; large radii are time-sliced.
- `moveTo` with `standoffOf` chooses a safe reachable position beside the block.
- `breakBlock` breaks exactly one explicit block; it never propagates to neighbours.
- The mining loop is driven by the building service; the AI owns selection and repetition.
