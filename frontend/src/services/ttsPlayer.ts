/**
 * TTS Player — 接收 SSE 推送的音频 chunks 并播放
 */

class TTSPlayer {
  private audioContext: AudioContext | null = null;
  private source: AudioBufferSourceNode | null = null;
  private chunks: AudioBuffer[] = [];
  private isPlaying = false;
  private onStateChange?: (state: 'idle' | 'playing') => void;

  setOnStateChange(cb: (state: 'idle' | 'playing') => void) {
    this.onStateChange = cb;
  }

  private getContext(): AudioContext {
    if (!this.audioContext) {
      this.audioContext = new AudioContext();
    }
    if (this.audioContext.state === 'suspended') {
      this.audioContext.resume();
    }
    return this.audioContext;
  }

  /** 添加 MP3 chunk 到播放队列 */
  async addChunk(base64Data: string): Promise<void> {
    try {
      const ctx = this.getContext();
      const binaryStr = atob(base64Data);
      const bytes = new Uint8Array(binaryStr.length);
      for (let i = 0; i < binaryStr.length; i++) {
        bytes[i] = binaryStr.charCodeAt(i);
      }

      // Decode MP3 to AudioBuffer
      const audioBuffer = await ctx.decodeAudioData(bytes.buffer);
      this.chunks.push(audioBuffer);

      if (!this.isPlaying) {
        this.playNext();
      }
    } catch (e) {
      console.warn('TTS chunk decode error:', e);
    }
  }

  private playNext() {
    if (this.chunks.length === 0) {
      this.isPlaying = false;
      this.onStateChange?.('idle');
      return;
    }

    this.isPlaying = true;
    this.onStateChange?.('playing');

    const ctx = this.getContext();
    const buffer = this.chunks.shift()!;
    this.source = ctx.createBufferSource();
    this.source.buffer = buffer;
    this.source.connect(ctx.destination);
    this.source.onended = () => this.playNext();
    this.source.start();
  }

  /** 停止播放并清空队列 */
  stop() {
    this.source?.stop();
    this.source = null;
    this.chunks = [];
    this.isPlaying = false;
    this.onStateChange?.('idle');
  }

  get state() {
    return this.isPlaying ? 'playing' : 'idle';
  }
}

export const ttsPlayer = new TTSPlayer();
