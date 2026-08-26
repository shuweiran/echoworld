import type { CSSProperties } from 'react';

export type IconName = string;

interface IconProps {
  name: IconName;
  size?: number;
  className?: string;
  title?: string;
}

const GLYPHS: Record<string, string> = {
  home: '⌂', book: '▤', users: '♟', info: 'ⓘ', settings: '⚙',
  'arrow-left': '←', 'arrow-right': '→', sparkles: '✦',
  moon: '☾', map: '◇', goal: '◎',
};

export function Icon({ name, size = 16, className, title }: IconProps) {
  const style: CSSProperties = {
    display: 'inline-flex', width: size, height: size, alignItems: 'center',
    justifyContent: 'center', fontSize: Math.max(12, Math.round(size * 0.9)), lineHeight: 1,
  };
  return (
    <span className={className} style={style} role={title ? 'img' : undefined}
      aria-label={title} aria-hidden={title ? undefined : true} title={title} data-icon={name}>
      {GLYPHS[name] ?? '•'}
    </span>
  );
}
