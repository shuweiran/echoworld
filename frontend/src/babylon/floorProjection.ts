import type { SimSnapshot } from '../phaser/simulationData';

export const FLOOR_HEIGHT_METERS = 3.2;

/** Pure display projection. It never writes server x/y/floor state. */
export function floorElevation(floorId?: string, floors?: SimSnapshot['floors']): number {
  if (!floorId || floorId === 'ground') return 0;
  const index = floors?.findIndex(floor => floor.id === floorId) ?? -1;
  if (index >= 0) return index * FLOOR_HEIGHT_METERS;
  const match = floorId.match(/(?:f|floor)[-_]?(\d+)/i);
  return match ? Math.max(0, Number(match[1]) - 1) * FLOOR_HEIGHT_METERS : 0;
}
