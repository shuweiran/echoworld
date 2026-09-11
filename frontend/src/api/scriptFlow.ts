import { API_ORIGIN } from './client';

export interface ScriptFlowIdentity {
  sessionId: string;
  player: string;
  playerKey?: string;
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function flowRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_ORIGIN}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...(init?.headers || {}),
    },
  });
  const text = await res.text();
  let body: any = {};
  if (text) {
    try { body = JSON.parse(text); } catch { body = { error: text }; }
  }
  if (!res.ok) throw new Error(body?.error || `HTTP ${res.status}`);
  return body as T;
}

function payload(identity: ScriptFlowIdentity, extra?: Record<string, unknown>) {
  return {
    session_id: identity.sessionId,
    player: identity.player,
    ...(identity.playerKey ? { player_key: identity.playerKey } : {}),
    ...(extra || {}),
  };
}

export const scriptFlowApi = {
  generateFull: (identity: ScriptFlowIdentity) =>
    flowRequest<any>('/api/script/flow/generate_full', {
      method: 'POST', body: JSON.stringify(payload(identity)),
    }),

  ensureOpening: (identity: ScriptFlowIdentity) =>
    flowRequest<any>('/api/script/flow/opening/ensure', {
      method: 'POST', body: JSON.stringify(payload(identity)),
    }),

  openingSay: (identity: ScriptFlowIdentity, message: string) =>
    flowRequest<any>('/api/script/flow/opening/say', {
      method: 'POST', body: JSON.stringify(payload(identity, { message })),
    }),

  startInvestigation: (identity: ScriptFlowIdentity) =>
    flowRequest<any>('/api/script/flow/investigation/start', {
      method: 'POST', body: JSON.stringify(payload(identity)),
    }),

  status: (identity: ScriptFlowIdentity) => {
    const qs = new URLSearchParams({
      session_id: identity.sessionId,
      player: identity.player,
      ...(identity.playerKey ? { player_key: identity.playerKey } : {}),
    });
    return flowRequest<any>(`/api/script/flow/status?${qs.toString()}`);
  },
};
