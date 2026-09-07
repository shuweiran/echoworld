export interface PlayerTurnGateOptions {
  liveMode: boolean;
  liveGameType: string;
  liveStatus: string;
  queueLen: number;
  typing: { done: boolean } | null;
  roundComplete: boolean;
  inputPending?: boolean;
}

/** 一般模式只有在整轮完成、输入已终态且当前播放稳定后才进入玩家回合。 */
export function isPlayerTurnGate(opts: PlayerTurnGateOptions): boolean {
  const playing = !!opts.typing && !opts.typing.done;
  return opts.liveMode
    && opts.liveGameType === 'general'
    && opts.liveStatus === 'open'
    && opts.roundComplete
    && !opts.inputPending
    && !playing
    && opts.queueLen <= 1;
}

export interface AutonomousAdvanceGateOptions {
  hasSession: boolean;
  identityResolved: boolean;
  hasPlayer: boolean;
  hasOverride: boolean;
  gameType: string;
  drained: boolean;
  busy: boolean;
}

/** 只有无真人玩家的纯 Agent 一般模式，才允许点击空对话框生成下一轮。 */
export function isAutonomousAdvanceGate(opts: AutonomousAdvanceGateOptions): boolean {
  return opts.hasSession
    && opts.identityResolved
    && !opts.hasPlayer
    && !opts.hasOverride
    && (opts.gameType === 'general' || opts.gameType === 'unknown')
    && opts.drained
    && !opts.busy;
}
