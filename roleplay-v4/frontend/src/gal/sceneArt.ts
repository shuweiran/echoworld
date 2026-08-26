const BACKGROUNDS: Record<string, string> = {
  '雨夜咖啡馆': '/art/general/cafe-rainy-night-v1.png',
  '沉没的圣奥古斯丁': '/art/sunken-st-augustine/background-v1.png',
};
const PORTRAITS: Record<string, string> = {
  'Airene Blake': '/art/sunken-st-augustine/airene-blake-v1.png',
  'Lillian Gray': '/art/sunken-st-augustine/lillian-gray-v1.png',
  'Marcus Wayne': '/art/sunken-st-augustine/marcus-wayne-v1.png',
  'Samuel Hopkins': '/art/sunken-st-augustine/samuel-hopkins-v1.png',
  'Victor Draco': '/art/sunken-st-augustine/victor-draco-v1.png',
};
function findArt(table: Record<string, string>, value: string | undefined): string | undefined {
  const key = (value ?? '').trim().toLocaleLowerCase();
  if (!key) return undefined;
  return Object.entries(table).find(([name]) => {
    const candidate = name.toLocaleLowerCase();
    return candidate === key || candidate.includes(key) || key.includes(candidate);
  })?.[1];
}
export function sceneBackgroundArt(scene: string | undefined): string | undefined {
  return findArt(BACKGROUNDS, scene);
}
export function scenePortraitArt(name: string | undefined): string | undefined {
  return findArt(PORTRAITS, name);
}
