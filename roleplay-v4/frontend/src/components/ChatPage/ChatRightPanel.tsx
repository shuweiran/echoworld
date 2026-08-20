/**
 * ChatRightPanel.tsx — 右侧操作面板（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：按模式渲染右侧状态/操作列——
 *   - 狼人杀：WerewolfStatePanel + WerewolfActionPanel + WerewolfResumePanel
 *   - 剧本杀：ScriptResumePanel + ScriptStatePanel（动作经 props 上抛给 ChatPage）
 *   - 一般/导演模式：不渲染（保持旧 2 列布局）
 * 视觉：统一 game-card 卡片化面板。
 */
import { useAppStore } from '../../store/appStore';
import { WerewolfStatePanel } from './werewolf/WerewolfStatePanel';
import { WerewolfActionPanel } from './werewolf/WerewolfActionPanel';
import { WerewolfResumePanel } from './werewolf/WerewolfResumePanel';
import { ScriptStatePanel } from './script/ScriptStatePanel';
import { ScriptResumePanel } from './script/ScriptResumePanel';

export interface ScriptPanelHandlers {
  scriptState: any;
  currentPlayer: string;
  scriptClues: any[];
  scriptPublicClues: any[];
  scriptReveal: any;
  scriptVoteTarget: string;
  setScriptVoteTarget: (n: string) => void;
  scriptSimulation: any;
  scriptBusy: boolean;
  scriptSearchMsg: string;
  transferTargets: Record<string, string>;
  setTransferTargets: (m: Record<string, string>) => void;
  onSearch: (location: string) => void;
  onTransferClue: (clueId: string, target: string) => void;
  onStartDiscussion: () => void;
  onStartVoting: () => void;
  onVote: () => void;
  /** P-0816-M：直接投指定嫌疑人（投票主区候选卡；POST /api/script/vote） */
  onVoteFor: (suspect: string) => void;
  /** P-0816-M：弃票（POST /api/script/vote abstain:true，决策 U8） */
  onAbstain: () => void;
  onResolve: () => void;
  onFinish: () => void;
  onRestart: () => void;
  onLeave: () => void;
  /** P-0815-F（方向1，根因 A）：SETUP 阶段手动生成完整剧本（后端 generate_full，异步） */
  onGenerateFull: () => void;
  onOpen2D: () => void;
  onBackToScene: () => void;
}

export function ChatRightPanel({ script }: { script: ScriptPanelHandlers }) {
  const store = useAppStore();

  if (store.mode === 'werewolf' && store.werewolfPhase !== 'game_over' && store.werewolfPhase !== 'ended') {
    return (
      <aside className="panel panel-werewolf">
        <div className="panel-body" style={{ padding: '8px' }}>
          <WerewolfStatePanel
            phase={store.werewolfPhase}
            round={store.werewolfRound}
            players={store.werewolfPlayers}
            myRole={store.werewolfMyRole}
          />
          <WerewolfActionPanel />
          <WerewolfResumePanel />
        </div>
      </aside>
    );
  }

  if (store.mode === 'script') {
    return (
      <aside className="panel panel-werewolf">
        <div className="panel-body" style={{ padding: '8px' }}>
          <ScriptResumePanel />
          <ScriptStatePanel
            state={script.scriptState}
            currentPlayer={script.currentPlayer}
            foundClues={script.scriptClues}
            publicClues={script.scriptPublicClues}
            reveal={script.scriptReveal}
            voteTarget={script.scriptVoteTarget}
            setVoteTarget={script.setScriptVoteTarget}
            simulation={script.scriptSimulation}
            busy={script.scriptBusy}
            searchMsg={script.scriptSearchMsg}
            transferTargets={script.transferTargets}
            setTransferTargets={script.setTransferTargets}
            onSearch={script.onSearch}
            onTransferClue={script.onTransferClue}
            onStartDiscussion={script.onStartDiscussion}
            onStartVoting={script.onStartVoting}
            onVote={script.onVote}
            onResolve={script.onResolve}
            onFinish={script.onFinish}
            onOpen2D={script.onOpen2D}
            onRestart={script.onRestart}
            onLeave={script.onLeave}
            onGenerateFull={script.onGenerateFull}
            onBackToScene={script.onBackToScene}
          />
        </div>
      </aside>
    );
  }

  return null;
}
