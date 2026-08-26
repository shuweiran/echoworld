import type { SVGProps } from 'react';

export type IconName =
  | 'book' | 'users' | 'sparkles' | 'moon' | 'settings' | 'map'
  | 'chat' | 'home' | 'search' | 'clue' | 'goal' | 'lock'
  | 'check' | 'warning' | 'download' | 'fullscreen' | 'volume'
  | 'arrow-right' | 'arrow-left' | 'info' | 'play' | 'sliders' | 'folder';

type IconProps = SVGProps<SVGSVGElement> & { name: IconName; size?: number };

const common = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.9, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };

export function Icon({ name, size = 18, ...props }: IconProps) {
  const shape = (() => {
    switch (name) {
      case 'book': return <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5z" /><path d="M4 5.5v16" /><path d="M8 7h8M8 11h7" /></>;
      case 'users': return <><path d="M16 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2" /><circle cx="9.5" cy="7" r="4" /><path d="M17 11a4 4 0 1 0-1.6-7.67M21 21v-2a4 4 0 0 0-2.9-3.85" /></>;
      case 'sparkles': return <><path d="m12 3-1.35 5.65L5 10l5.65 1.35L12 17l1.35-5.65L19 10l-5.65-1.35z" /><path d="m19 16-.65 2.35L16 19l2.35.65L19 22l.65-2.35L22 19l-2.35-.65zM5 3l-.5 1.5L3 5l1.5.5L5 7l.5-1.5L7 5l-1.5-.5z" /></>;
      case 'moon': return <path d="M20.5 15.3A8.5 8.5 0 0 1 8.7 3.5 8.5 8.5 0 1 0 20.5 15.3z" />;
      case 'settings': return <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.12 2.12-.06-.06a1.7 1.7 0 0 0-1.88-.34 1.7 1.7 0 0 0-1.04 1.56v.08h-3v-.08a1.7 1.7 0 0 0-1.04-1.56 1.7 1.7 0 0 0-1.88.34l-.06.06-2.12-2.12.06-.06A1.7 1.7 0 0 0 7 15a1.7 1.7 0 0 0-1.56-1.04h-.08v-3h.08A1.7 1.7 0 0 0 7 9.92a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.12-2.12.06.06A1.7 1.7 0 0 0 10.66 6a1.7 1.7 0 0 0 1.04-1.56v-.08h3v.08A1.7 1.7 0 0 0 15.74 6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.12 2.12-.06.06A1.7 1.7 0 0 0 19.4 9.66a1.7 1.7 0 0 0 1.56 1.04h.08v3h-.08A1.7 1.7 0 0 0 19.4 15z" /></>;
      case 'map': return <><path d="m9 18-6 3V6l6-3 6 3 6-3v15l-6 3z" /><path d="M9 3v15M15 6v15" /></>;
      case 'chat': return <path d="M20 15a4 4 0 0 1-4 4H8l-4 3v-3.5A4 4 0 0 1 2 15V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z" />;
      case 'home': return <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" /></>;
      case 'search': return <><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 4.5 4.5" /></>;
      case 'clue': return <><path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5z" /><path d="m4 7.5 8 4.5 8-4.5M12 12v9" /></>;
      case 'goal': return <><path d="M5 22V3" /><path d="M5 4c4-2 7 2 12 0v10c-5 2-8-2-12 0" /></>;
      case 'lock': return <><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></>;
      case 'check': return <path d="m5 12 4.5 4.5L19 7" />;
      case 'warning': return <><path d="m12 3 9 17H3z" /><path d="M12 9v4M12 17h.01" /></>;
      case 'download': return <><path d="M12 3v12M7 10l5 5 5-5M4 21h16" /></>;
      case 'fullscreen': return <path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5" />;
      case 'volume': return <><path d="M4 10v4h4l5 4V6L8 10z" /><path d="M16 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12" /></>;
      case 'info': return <><circle cx="12" cy="12" r="9" /><path d="M12 10v6M12 7h.01" /></>;
      case 'play': return <path d="m9 5 10 7-10 7z" />;
      case 'sliders': return <><path d="M4 6h16M4 12h16M4 18h16" /><circle cx="9" cy="6" r="2" /><circle cx="15" cy="12" r="2" /><circle cx="11" cy="18" r="2" /></>;
      case 'folder': return <path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />;
      case 'arrow-left': return <path d="m14 6-6 6 6 6M8 12h12" />;
      default: return <path d="M5 12h14M13 6l6 6-6 6" />;
    }
  })();

  return <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false" {...common} {...props}>{shape}</svg>;
}
