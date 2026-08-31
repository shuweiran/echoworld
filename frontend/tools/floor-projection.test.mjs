import assert from 'node:assert/strict';
import test from 'node:test';
import { floorElevation, FLOOR_HEIGHT_METERS } from '../src/babylon/floorProjection.ts';
import { normalizeSnapshot, projectSnapshotToFloor } from '../src/phaser/simulationData.ts';

test('renderer projections preserve authoritative x/y/floor/path/track facts', () => {
  const source = {
    floors: [{ id: 'f1' }, { id: 'f2' }, { id: 'f3' }],
    agents: [{
      agentName: 'A', x: 12, y: 34, floorId: 'f2', track: 'WEAK',
      navigationWaypoints: [
        { x: 12, y: 34, floorId: 'f1', transition: false, connectorId: '' },
        { x: 12, y: 34, floorId: 'f2', transition: true, connectorId: 'stairs' },
      ],
    }],
    obstacles: [{ type: 'WALL', x: 0, y: 0, width: 1, height: 1, floorId: 'f1' }],
  };
  const before = structuredClone(source);
  const normalized = normalizeSnapshot(source);
  const phaserF1 = projectSnapshotToFloor(normalized, 'f1');
  const phaserF2 = projectSnapshotToFloor(normalized, 'f2');

  assert.equal(phaserF1.agents.length, 0);
  assert.equal(phaserF2.agents.length, 1);
  assert.equal(floorElevation('f2', normalized.floors), FLOOR_HEIGHT_METERS);
  assert.equal(floorElevation('f3', normalized.floors), FLOOR_HEIGHT_METERS * 2);
  assert.deepEqual(source, before, '2D/3D projection must not mutate the server snapshot');
  assert.deepEqual(phaserF2.agents[0].navigationWaypoints, normalized.agents[0].navigationWaypoints);
  assert.equal(phaserF2.agents[0].track, 'WEAK');
  assert.deepEqual(
    [normalized.agents[0].x, normalized.agents[0].y, normalized.agents[0].floorId],
    [12, 34, 'f2'],
  );
});
