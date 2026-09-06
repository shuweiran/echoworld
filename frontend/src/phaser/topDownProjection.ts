/**
 * Tilted 2.5D display cues. This is deliberately presentation-only: all input,
 * collision and server facts continue to use the unprojected world x/y plane.
 */
export function perspectiveScaleAtY(y: number, worldHeight: number): number {
  const depth = Math.max(0, Math.min(1, y / Math.max(1, worldHeight)));
  return 0.72 + depth * 0.42;
}

export function standingDepth(y: number, worldHeight: number, base = 10): number {
  const depth = Math.max(0, Math.min(1, y / Math.max(1, worldHeight)));
  return base + depth * 30;
}

export function southFaceHeight(objectHeight: number): number {
  return Math.max(8, Math.min(28, objectHeight * 0.2));
}
