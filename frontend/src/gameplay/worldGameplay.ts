export type WorldAction = 'LOOK_AT' | 'OPEN' | 'CLOSE' | 'PICK_UP' | 'PUT_DOWN' | 'USE' | 'SIT';

export interface WorldObjectView {
  id: string;
  type: string;
  displayName: string;
  description?: string;
  x: number;
  y: number;
  floorId: string;
  carriedBy?: string;
  portable?: boolean;
  consumable?: boolean;
  active: boolean;
  supportedActions: WorldAction[];
  state?: Record<string, unknown>;
}

export interface MetricView {
  key: string;
  label: string;
  value: number;
  min: number;
  max: number;
  unit: string;
}

export interface GameplayView {
  agentId: string;
  revision: number;
  metrics: Record<string, MetricView>;
  inventory: WorldObjectView[];
  capacity: number;
  worldVersion: number;
}

export function normalizeWorldObject(raw: unknown): WorldObjectView | null {
  if (!raw || typeof raw !== 'object') return null;
  const value = raw as Record<string, unknown>;
  const id = String(value.id ?? '');
  if (!id) return null;
  return {
    id,
    type: String(value.type ?? 'OBJECT'),
    displayName: String(value.displayName ?? value.type ?? id),
    description: typeof value.description === 'string' ? value.description : '',
    x: Number(value.x ?? 0), y: Number(value.y ?? 0),
    floorId: String(value.floorId ?? 'ground'),
    carriedBy: String(value.carriedBy ?? ''),
    portable: Boolean(value.portable), consumable: Boolean(value.consumable),
    active: value.active !== false,
    supportedActions: Array.isArray(value.supportedActions)
      ? value.supportedActions.map(String).filter(Boolean) as WorldAction[] : [],
    state: value.state && typeof value.state === 'object' ? value.state as Record<string, unknown> : {},
  };
}

export async function submitWorldAction(actorId: string, action: WorldAction, targetId: string,
                                        worldVersion?: number, capability: 'SELF' | 'MASTER' = 'SELF') {
  const response = await fetch('/api/simulation/actions', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ actor_id: actorId, action, target_id: targetId, capability,
      based_on_world_version: worldVersion }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(String(payload.error || payload.message || `HTTP ${response.status}`));
  return payload as Record<string, unknown>;
}

export function nearbyObjects(objects: WorldObjectView[], actor: { x: number; y: number; floorId?: string }, radius = 150) {
  const floor = actor.floorId || 'ground';
  return objects.filter(object => object.active && !object.carriedBy && object.floorId === floor
    && Math.hypot(object.x - actor.x, object.y - actor.y) <= radius)
    .sort((a, b) => Math.hypot(a.x - actor.x, a.y - actor.y) - Math.hypot(b.x - actor.x, b.y - actor.y));
}
