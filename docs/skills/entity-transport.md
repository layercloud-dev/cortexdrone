# Skill: Transport an Entity

This is a composition skill, not a Java task. The AI finds an entity, picks it
up, flies to the destination, then unpicks it.

```js
async function transportEntity(entityId, x, y, z) {
  const picked = await drone.pickEntity(entityId);
  if (!picked || picked.ok === false || picked.picked !== true) return picked;

  const moved = await drone.moveTo(x, y, z, { route: 'auto' });
  if (!moved || moved.ok === false || moved.arrived !== true) return moved;

  return await drone.unpickEntity({ entityId });
}
```

Typical use: find a nearby player or mob first.

```js
const found = await drone.inspect('entities', { radius: 8, filter: 'player' });
const target = found.entities && found.entities[0];
if (!target) return { error: 'no nearby player' };
return await transportEntity(target.id, dest.x, dest.y, dest.z);
```

Save it as a reusable skill once verified:

```js
await drone.skills('save', {
  name: 'transport_entity',
  description: 'Pick up an entity, fly to a location, then unpick it.',
  code: '...the function above...',
  tags: 'entity,transport,pick,unpick'
});
```

Primitives used:

- `pickEntity(entityId)` — mounts one entity on the drone; moves into reach first.
- `moveTo(x, y, z, opts)` — normal flight/navigation.
- `unpickEntity({entityId?})` — dismounts the current rider.
