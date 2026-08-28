import {
  Color3,
  LoadAssetContainerAsync,
  MeshBuilder,
  Quaternion,
  Scene,
  Space,
  StandardMaterial,
  TransformNode,
} from '@babylonjs/core';
import type { AbstractMesh, AssetContainer, Bone, Skeleton } from '@babylonjs/core';
import '@babylonjs/loaders/glTF';

export type AvatarMotionSemantic = 'idle' | 'walk' | 'run' | 'talk';

interface PosedBone {
  bone: Bone;
  rest: Quaternion;
}

export interface PrivateMmdAvatar {
  container: AssetContainer;
  pivot: TransformNode;
  meshes: AbstractMesh[];
  marker: AbstractMesh;
  markerMaterial: StandardMaterial;
  bones: Partial<Record<'leftArm' | 'rightArm' | 'leftLeg' | 'rightLeg' | 'upperBody' | 'neck', PosedBone>>;
  groundY: number;
  sourceName: string;
}

export const PRIVATE_GLTF_MODEL_URL = '/models/private/avatar/model.glb';
export const PRIVATE_MMD_MODEL_URL = '/models/private/avatar/model.pmx';
export const PRIVATE_MMD_MODEL_LABEL = '本地私人角色模型';

const BONE_ALIASES = {
  leftArm: ['左腕', '左腕D', 'left arm', 'arm_l'],
  rightArm: ['右腕', '右腕D', 'right arm', 'arm_r'],
  leftLeg: ['左足', '左足D', 'left leg', 'leg_l'],
  rightLeg: ['右足', '右足D', 'right leg', 'leg_r'],
  upperBody: ['上半身', '上半身2', 'upper body', 'spine'],
  neck: ['首', 'neck'],
} as const;

function findBone(skeleton: Skeleton | undefined, aliases: readonly string[]): PosedBone | undefined {
  if (!skeleton) return undefined;
  const normalized = aliases.map(alias => alias.toLocaleLowerCase());
  const bone = skeleton.bones.find(candidate => {
    const name = candidate.name.toLocaleLowerCase();
    return normalized.some(alias => name === alias || name.includes(alias));
  });
  if (!bone) return undefined;
  return { bone, rest: bone.getRotationQuaternion(Space.LOCAL).clone() };
}

function boundsOf(meshes: AbstractMesh[]) {
  let minY = Number.POSITIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  let minX = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let minZ = Number.POSITIVE_INFINITY;
  let maxZ = Number.NEGATIVE_INFINITY;
  meshes.forEach(mesh => {
    mesh.computeWorldMatrix(true);
    const box = mesh.getBoundingInfo().boundingBox;
    minY = Math.min(minY, box.minimumWorld.y); maxY = Math.max(maxY, box.maximumWorld.y);
    minX = Math.min(minX, box.minimumWorld.x); maxX = Math.max(maxX, box.maximumWorld.x);
    minZ = Math.min(minZ, box.minimumWorld.z); maxZ = Math.max(maxZ, box.maximumWorld.z);
  });
  return { minY, maxY, width: maxX - minX, depth: maxZ - minZ };
}

async function loadModelContainer(scene: Scene): Promise<{ container: AssetContainer; format: 'GLB' | 'PMX' }> {
  try {
    const container = await LoadAssetContainerAsync(PRIVATE_GLTF_MODEL_URL, scene, { pluginExtension: '.glb' });
    return { container, format: 'GLB' };
  } catch (glbError) {
    console.warn('[EchoWorld 3D] GLB 私人模型加载失败，尝试原始 PMX', glbError);
    await Promise.all([
      import('babylon-mmd/esm/Loader/mmdModelLoader.default'),
      import('babylon-mmd/esm/Loader/pmxLoader'),
    ]);
    try {
      const container = await LoadAssetContainerAsync(PRIVATE_MMD_MODEL_URL, scene, { pluginExtension: '.pmx' });
      return { container, format: 'PMX' };
    } catch (pmxError) {
      throw new AggregateError([glbError, pmxError], 'GLB and PMX private model loading both failed');
    }
  }
}

export async function loadPrivateMmdAvatar(scene: Scene, parent: TransformNode, agentName: string): Promise<PrivateMmdAvatar> {
  const { container, format } = await loadModelContainer(scene);
  if (scene.isDisposed) {
    container.dispose();
    throw new Error('scene disposed while loading the private PMX model');
  }
  container.addAllToScene();

  const meshes = container.meshes.filter(mesh => mesh.getTotalVertices() > 0);
  if (meshes.length === 0) {
    container.dispose();
    throw new Error('PMX model contains no renderable mesh');
  }
  const bounds = boundsOf(meshes);
  const height = Math.max(0.001, bounds.maxY - bounds.minY);
  const horizontal = Math.max(0.001, bounds.width, bounds.depth);
  const scale = Math.min(1.82 / height, 1.35 / horizontal);

  const pivot = new TransformNode(`private-mmd-${agentName}`, scene);
  pivot.parent = parent;
  pivot.scaling.setAll(scale);
  pivot.rotation.y = Math.PI;
  const groundY = -bounds.minY * scale;
  pivot.position.y = groundY;
  container.rootNodes.forEach(node => { node.parent = pivot; });

  meshes.forEach(mesh => {
    mesh.isPickable = true;
    mesh.metadata = { ...(mesh.metadata || {}), echoworldAgent: agentName, privateLocalAsset: true };
  });

  const markerMaterial = new StandardMaterial(`private-mmd-marker-${agentName}`, scene);
  markerMaterial.diffuseColor = new Color3(0.96, 0.72, 0.12);
  markerMaterial.emissiveColor = new Color3(0.34, 0.2, 0.02);
  const marker = MeshBuilder.CreateTorus(`private-mmd-marker-${agentName}`, { diameter: 1.12, thickness: 0.055, tessellation: 32 }, scene);
  marker.parent = parent;
  marker.position.y = 0.035;
  marker.material = markerMaterial;
  marker.isPickable = false;
  marker.setEnabled(false);

  const skeleton = container.skeletons[0];
  return {
    container,
    pivot,
    meshes,
    marker,
    markerMaterial,
    groundY,
    sourceName: `${PRIVATE_MMD_MODEL_LABEL} · ${format}`,
    bones: {
      leftArm: findBone(skeleton, BONE_ALIASES.leftArm),
      rightArm: findBone(skeleton, BONE_ALIASES.rightArm),
      leftLeg: findBone(skeleton, BONE_ALIASES.leftLeg),
      rightLeg: findBone(skeleton, BONE_ALIASES.rightLeg),
      upperBody: findBone(skeleton, BONE_ALIASES.upperBody),
      neck: findBone(skeleton, BONE_ALIASES.neck),
    },
  };
}

function setBoneDelta(posed: PosedBone | undefined, pitch = 0, yaw = 0, roll = 0) {
  if (!posed) return;
  const delta = Quaternion.RotationYawPitchRoll(yaw, pitch, roll);
  posed.bone.setRotationQuaternion(posed.rest.multiply(delta), Space.LOCAL);
}

export function updatePrivateMmdAvatar(avatar: PrivateMmdAvatar, semantic: AvatarMotionSemantic, phase: number, selected: boolean) {
  const moving = semantic === 'walk' || semantic === 'run';
  const amplitude = semantic === 'run' ? 0.62 : moving ? 0.38 : 0;
  const stride = Math.sin(phase) * amplitude;
  const bob = moving ? Math.abs(Math.sin(phase * 2)) * (semantic === 'run' ? 0.045 : 0.025) : Math.sin(phase * 0.45) * 0.008;
  avatar.pivot.position.y = avatar.groundY + bob;
  avatar.marker.setEnabled(selected);

  setBoneDelta(avatar.bones.leftArm, stride * 0.72, 0, -0.04);
  setBoneDelta(avatar.bones.rightArm, -stride * 0.72, 0, 0.04);
  setBoneDelta(avatar.bones.leftLeg, -stride, 0, 0);
  setBoneDelta(avatar.bones.rightLeg, stride, 0, 0);
  setBoneDelta(avatar.bones.upperBody, semantic === 'run' ? 0.08 : 0, semantic === 'talk' ? Math.sin(phase * 0.55) * 0.035 : 0, 0);
  setBoneDelta(avatar.bones.neck, 0, semantic === 'talk' ? Math.sin(phase * 0.8) * 0.055 : 0, semantic === 'idle' ? Math.sin(phase * 0.32) * 0.018 : 0);
}

export function disposePrivateMmdAvatar(avatar: PrivateMmdAvatar) {
  avatar.marker.dispose();
  avatar.markerMaterial.dispose();
  avatar.container.dispose();
  avatar.pivot.dispose();
}
