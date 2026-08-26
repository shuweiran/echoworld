/** 当前场景的人工审核美术包。运行时生图存在时仍优先用角色自己的图。 */
const SUNKEN_CHURCH = '/art/sunken-st-augustine';

const CHURCH_PORTRAITS: Record<string, string> = {
  '艾琳·布莱克': `${SUNKEN_CHURCH}/airene-blake-v1.png`,
  '马库斯·韦恩': `${SUNKEN_CHURCH}/marcus-wayne-v1.png`,
  '塞缪尔·霍普金斯': `${SUNKEN_CHURCH}/samuel-hopkins-v1.png`,
  '莉莉安·格雷': `${SUNKEN_CHURCH}/lillian-gray-v1.png`,
  '维克托·德拉科': `${SUNKEN_CHURCH}/victor-draco-v1.png`,
};

export function sceneBackgroundArt(scene: string): string | undefined {
  return /沉没的圣·奥古斯丁教堂/.test(scene || '')
    ? `${SUNKEN_CHURCH}/background-v1.png` : undefined;
}

export function scenePortraitArt(name: string): string | undefined {
  return CHURCH_PORTRAITS[name];
}
