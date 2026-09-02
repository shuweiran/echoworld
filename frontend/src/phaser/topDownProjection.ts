/** Near-vertical top-down 2.5D cues. Authoritative world coordinates never pass through this module. */
export function perspectiveScaleAtY(y: number, worldHeight: number): number {
  const depth = Math.max(0, Math.min(1, y / Math.max(1, worldHeight)));
  return 0.88 + depth * 0.18;
}

export function standingDepth(y: number, worldHeight: number, base = 10): number {
  const depth = Math.max(0, Math.min(1, y / Math.max(1, worldHeight)));
  return base + depth * 30;
}

export function southFaceHeight(objectHeight: number): number {
  return Math.max(4, Math.min(14, objectHeight * 0.08));
}
