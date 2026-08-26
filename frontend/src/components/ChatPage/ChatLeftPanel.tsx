/**
 * ChatLeftPanel.tsx — 左侧角色面板（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：角色列表管理（添加/移除/语音开关/按角色过滤消息流），自包含
 * （读 useAppStore + 调 api），展示用卡片化色块：活跃角色彩色点 + 状态徽标。
 */
import { useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import { colorFor } from './chatUtils';

export function ChatLeftPanel() {
  const store = useAppStore();
  const [showAddAgent, setShowAddAgent] = useState(false);

  const handleAddAgent = async (name: string) => {
    setShowAddAgent(false);
    if (store.isRunning) await store.stop();
    await api.addAgent(name);
    store.addAgent(name, 'active');
    await store.loadState();
  };

  const handleRemoveAgent = async (name: string) => {
    if (store.isRunning) await store.stop();
    await store.removeAgent(name);
    await store.loadState();
  };

  const inactiveCharacters = store.characters.filter(
    (c: any) => !store.agents.includes(c.name)
  );

  return (
    <aside className="panel panel-left game-left-panel">
      <div className="panel-body">
        <div className="section">
          <div className="label" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span>👥 角色</span>
            <button className="btn btn-smallall btn-primary"
              onClick={() => setShowAddAgent(!showAddAgent)}
              title="添加角色">
              +
            </button>
          </div>
          {showAddAgent && (
            <div className="dropdown-agent" style={{
              marginTop: 4, maxHeight: 200, overflowY: 'auto',
              background: 'var(--bg-2)', borderRadius: 6, border: '1px solid var(--border)',
            }}>
              {inactiveCharacters.length === 0 ? (
                <div style={{ padding: '8px 10px', fontSize: 12, color: 'var(--text-3)' }}>所有角色已在会话中</div>
              ) : inactiveCharacters.map((ch: any) => (
                <div key={ch.name}
                  className="dropdown-agent-item"
                  onClick={() => handleAddAgent(ch.name)}
                  style={{
                    padding: '6px 10px', cursor: 'pointer', fontSize: 13,
                    borderBottom: '1px solid var(--border)',
                  }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--bg-3)')}
                  onMouseLeave={e => (e.currentTarget.style.background = '')}
                >
                  <span style={{ fontWeight: 600 }}>{ch.name}</span>
                  <span style={{ color: 'var(--text-3)', marginLeft: 8, fontSize: 11 }}>
                    {ch.persona ? ch.persona.slice(0, 24) : ''}
                  </span>
                </div>
              ))}
            </div>
          )}
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
            <button className={`btn btn-smallall ${!store.historyFilter ? 'btn-primary' : ''}`} onClick={() => store.setHistoryFilter(null)}>全部</button>
            {store.agents.map(name => {
              const active = store.historyFilter === name;
              return (
                <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                  <button className={`actor-chip ${active ? 'selected' : ''}`} onClick={() => store.setHistoryFilter(active ? null : name)}>
                    <span className="avatar" style={{ background: colorFor(name), width: 16, height: 16, fontSize: 9 }}>{name[0]}</span>
                    {name}
                  </button>
                  <button className="btn btn-smallall btn-icon"
                    onClick={() => store.toggleVoice(name)}
                    style={{
                      padding: '0 4px', fontSize: 11, lineHeight: '20px', height: 20,
                      color: store.voiceMap[name] ? '#7c4dff' : '#888',
                      background: store.voiceMap[name] ? '#7c4dff22' : 'transparent',
                      border: '1px solid ' + (store.voiceMap[name] ? '#7c4dff55' : '#444'),
                    }}
                    title={store.voiceMap[name] ? '关闭语音' : '开启语音'}>
                    {store.voiceMap[name] ? '🔊' : '🔇'}
                  </button>
                  <button className="btn btn-smallall btn-icon"
                    onClick={() => handleRemoveAgent(name)}
                    style={{ padding: '0 3px', fontSize: 11, lineHeight: '20px', height: 20, color: 'var(--text-3)' }}
                    title={`移除 ${name}`}>
                    ×
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </aside>
  );
}
