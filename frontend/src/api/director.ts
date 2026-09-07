import { API_ORIGIN, getPlayerId } from './client';

export interface DirectorState {
  preflight_id: string;
  runtime_session_id?: string;
  scene_id: string;
  scene_description?: string;
  player: Record<string, unknown>;
  cast: Array<Record<string, unknown>>;
  relationships: string[];
  entry_order: string[];
  onstage: string[];
  offstage: string[];
  scene_notes: string[];
  confirmed: boolean;
  messages: Array<{ role: string; content: string; at?: string }>;
}

export interface DirectorRelationship {
  from: string;
  to: string;
  relation: string;
  detail: string;
}

export interface DirectorStoryPlan {
  premise?: string;
  tone?: string;
  opening_situation?: string;
  stakes?: string;
  scene_identities?: Record<string, string>;
  structured_relationships?: DirectorRelationship[];
  character_goals?: Record<string, string>;
  character_secrets?: Record<string, string>;
  story_beats?: string[];
  opening_events?: string[];
  world_facts?: string[];
  your_goal?: string;
  your_secret?: string;
  hidden_plan?: boolean;
}

export interface DirectorResponse {
  reply?: string;
  applied?: string[];
  rejected?: string[];
  state?: DirectorState;
  story_plan?: DirectorStoryPlan;
  active_agents?: string[];
  session_id?: string;
  goals?: unknown;
  director_state?: DirectorState;
  [key: string]: unknown;
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function directorRequest(path: string, init?: RequestInit): Promise<DirectorResponse> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 120000);
  try {
    const response = await fetch(`${API_ORIGIN}${path}`, {
      ...init,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...authHeaders(),
        ...(init?.headers || {}),
      },
    });
    const text = await response.text();
    let body: DirectorResponse = {};
    if (text) {
      try { body = JSON.parse(text) as DirectorResponse; }
      catch { body = { error: text }; }
    }
    if (!response.ok) throw new Error(String(body.error || `HTTP ${response.status}`));
    return body;
  } finally {
    window.clearTimeout(timer);
  }
}

export const directorApi = {
  playerId: getPlayerId,
  createPreflight: (body: Record<string, unknown>) => directorRequest('/api/director/preflight', {
    method: 'POST', body: JSON.stringify(body),
  }),
  getPreflight: (preflightId: string) =>
    directorRequest(`/api/director/preflight/${encodeURIComponent(preflightId)}`),
  preflightChat: (preflightId: string, message: string) =>
    directorRequest(`/api/director/preflight/${encodeURIComponent(preflightId)}/chat`, {
      method: 'POST', body: JSON.stringify({ message }),
    }),
  start: (preflightId: string) =>
    directorRequest(`/api/director/preflight/${encodeURIComponent(preflightId)}/start`, { method: 'POST' }),
  runtimeState: (sessionId: string) =>
    directorRequest(`/api/director/state?session_id=${encodeURIComponent(sessionId)}`),
  runtimeChat: (sessionId: string, message: string) =>
    directorRequest('/api/director/chat', {
      method: 'POST', body: JSON.stringify({ session_id: sessionId, message }),
    }),
};