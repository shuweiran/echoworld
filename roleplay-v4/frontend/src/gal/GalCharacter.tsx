/**
 * GalCharacter.tsx — 立绘组件（P-0810-02 + P-0810-06）
 *
 * 程序化像素立绘：speaker.sprite 字符模板 + palette 调色板 → SVG（crispEdges）。
 * 说话者：放大置前（scale 1.15 + z-index 提升 + 名字高亮）；非说话者：半透明置灰。
 * 新角色淡入：active 切换时 CSS animation 重新触发（animation-name none→galFadeIn）。
 *
 * P-0810-03：imageUrl prop——有真实 Pony 立绘渲染 <img>（pixelated），无则回退像素占位。
 * P-0810-06：placeholder speaker（真实对局未知角色）→ 渲染姓名首字占位（GalNamePlate）。
 */
import type { GalSpeaker } from './galDemoData';

interface SpriteProps {
  speaker: GalSpeaker;
  /** 每像素单元格放大倍数（SVG 显示尺寸 = 12*size × 16*size） */
  size?: number;
}

/** 纯像素画 SVG（立绘与对话框小头像共用） */
export function GalSprite({ speaker, size = 4 }: SpriteProps) {
  const rows = speaker.sprite;
  const w = Math.max(...rows.map(r => r.length));
  const h = rows.length;
  const cells: React.ReactNode[] = [];
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      const ch = row[x];
      if (ch === '.') continue;
      const fill = speaker.palette[ch];
      if (!fill) continue;
      cells.push(<rect key={`${x}-${y}`} x={x} y={y} width={1} height={1} fill={fill} />);
    }
  });
  return (
    <svg
      className="gal-sprite"
      width={w * size}
      height={h * size}
      viewBox={`0 0 ${w} ${h}`}
      shapeRendering="crispEdges"
      style={{ imageRendering: 'pixelated' }}
      role="img"
      aria-label={speaker.name}
    >
      {cells}
    </svg>
  );
}

/**
 * P-0810-06：未知对局角色占位立绘（SVG 姓名首字）。
 * 真实 SSE 流的角色名无法预知像素模板 → 深色底 + 首字大号像素风文字 + 角色色相点缀。
 */
export function GalNamePlate({ speaker, size = 4 }: SpriteProps) {
  const w = Math.max(...speaker.sprite.map(r => r.length));
  const h = speaker.sprite.length;
  const ch = (speaker.name || '?').slice(0, 1);
  return (
    <svg
      className="gal-sprite gal-name-plate"
      width={w * size}
      height={h * size}
      viewBox={`0 0 ${w} ${h}`}
      shapeRendering="crispEdges"
      style={{ imageRendering: 'pixelated' }}
      role="img"
      aria-label={`${speaker.name}（占位）`}
    >
      <rect x={0} y={0} width={w} height={h} fill={`hsl(${speaker.hue} 45% 16%)`} />
      <rect x={1} y={1} width={w - 2} height={h - 2} fill="none" stroke={`hsl(${speaker.hue} 80% 60%)`} strokeWidth={0.4} strokeDasharray="1 1" />
      <text
        x={w / 2}
        y={h / 2 + 4.5}
        textAnchor="middle"
        fontSize={11}
        fontWeight="bold"
        fill={speaker.color}
        fontFamily="'Courier New', 'NSimSun', monospace"
      >
        {ch}
      </text>
    </svg>
  );
}

interface GalCharacterProps {
  speaker: GalSpeaker;
  active: boolean;
  size?: number;
  /** P-0810-03：真实 Pony 立绘 URL（有值渲染 <img>，无值回退像素占位） */
  imageUrl?: string;
  /** P-0810-18：分层布局填充模式——立绘高度撑满槽位（height 100%，宽度按比例），真实图不做像素化 */
  fill?: boolean;
  /** P-0810-18：非透明底图（无 _t 版）时底部渐隐兜底（CSS mask），无此标记不做 */
  masked?: boolean;
}

export function GalCharacter({ speaker, active, size = 4, imageUrl, fill, masked }: GalCharacterProps) {
  const w = Math.max(...speaker.sprite.map(r => r.length));
  const h = speaker.sprite.length;
  return (
    <div className={`gal-char${active ? ' gal-char-active' : ''}${fill ? ' gal-char-fill' : ''}`}>
      <div
        className="gal-char-sprite"
        style={{
          background: `linear-gradient(180deg, hsl(${speaker.hue} 55% 20%), hsl(${speaker.hue} 40% 10%))`,
          boxShadow: `0 0 0 3px ${speaker.color}${active ? 'aa' : '33'}, 0 0 24px ${speaker.color}${active ? '55' : '11'}`,
        }}
      >
        {imageUrl ? (
          <img
            className={`gal-char-img${fill ? ' gal-char-img-fill' : ''}${masked ? ' gal-char-img-mask' : ''}`}
            src={imageUrl}
            alt={speaker.name}
            draggable={false}
            style={fill
              ? { imageRendering: 'auto' }
              : { width: w * size, height: h * size, imageRendering: 'pixelated' }}
          />
        ) : speaker.placeholder ? (
          <GalNamePlate speaker={speaker} size={size} />
        ) : (
          <GalSprite speaker={speaker} size={size} />
        )}
        <span className="gal-char-scan" aria-hidden />
      </div>
      <div className="gal-char-name" style={{ color: active ? speaker.color : undefined }}>
        {speaker.name}
        {active && <span className="gal-char-speaking">●</span>}
      </div>
      <div className="gal-char-title">{speaker.placeholder ? '对局角色（未注册立绘）' : speaker.title}</div>
    </div>
  );
}
