/**
 * AvatarPicker.tsx — 头像选择（共享）
 */
import { AVATARS } from '../mockData';

export function AvatarPicker({ value, onChange }: { value: string; onChange: (a: string) => void }) {
  return (
    <div className="av-pick">
      {AVATARS.map(a => (
        <button key={a} type="button" className={`av-item ${a === value ? 'active' : ''}`} onClick={() => onChange(a)}>{a}</button>
      ))}
    </div>
  );
}
