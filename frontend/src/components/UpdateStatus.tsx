import { useEffect, useState } from 'react';
import { Icon } from './ui/Icon';

type UpdateState = { status: string; version?: string | null; percent?: number; message?: string };
const desktop = () => (window as any).roleplayDesktop;

export function UpdateStatus() {
  const [state, setState] = useState<UpdateState | null>(null);
  useEffect(() => {
    const api = desktop();
    if (!api?.isDesktop) return;
    api.updates.state().then(setState);
    return api.updates.onStatus(setState);
  }, []);
  if (!state || state.status === 'unavailable') return null;
  const update = desktop().updates;
  const action = state.status === 'available'
    ? <button onClick={() => update.download()}>下载</button>
    : state.status === 'downloaded'
      ? <button onClick={() => update.install()}>重启安装</button>
      : <button onClick={() => update.check()} aria-label="检查更新"><Icon name="download" size={14} /></button>;
  return <div className={`update-status update-${state.status}`} title={state.message}>
    <Icon name="download" size={14} />
    <span>{state.status === 'downloading' ? `${state.percent || 0}%` : state.message || '更新'}</span>
    {action}
  </div>;
}
