import { ArcRotateCamera, Scene, UniversalCamera, Vector3 } from '@babylonjs/core';

export type PlayerCameraMode = 'first-person' | 'third-person';

const PLAYER_EYE_HEIGHT = 1.58;
const THIRD_PERSON_TARGET_HEIGHT = 1.15;
const THIRD_PERSON_DISTANCE = 7.5;

/**
 * Pure presentation camera rig. It follows an interpolated player transform and
 * converts explicit keyboard input into a camera-relative ground direction, but
 * never changes the player transform or sends gameplay commands itself.
 */
export class PlayerCameraRig {
  readonly thirdPerson: ArcRotateCamera;
  readonly firstPerson: UniversalCamera;

  private readonly scene: Scene;
  private readonly canvas: HTMLCanvasElement;
  private mode: PlayerCameraMode = 'third-person';
  private firstPersonHeadingInitialized = false;
  private lastPlanarForward = new Vector3(0, 0, 1);

  constructor(scene: Scene, canvas: HTMLCanvasElement) {
    this.scene = scene;
    this.canvas = canvas;
    this.thirdPerson = new ArcRotateCamera(
      'third-person-camera',
      -Math.PI / 2,
      1.05,
      THIRD_PERSON_DISTANCE,
      new Vector3(0, THIRD_PERSON_TARGET_HEIGHT, 0),
      scene,
    );
    this.thirdPerson.lowerRadiusLimit = 3.5;
    this.thirdPerson.upperRadiusLimit = 28;
    this.thirdPerson.lowerBetaLimit = 0.35;
    this.thirdPerson.upperBetaLimit = Math.PI / 2 - 0.06;
    this.thirdPerson.wheelPrecision = 35;
    this.thirdPerson.panningSensibility = 0;
    this.thirdPerson.inertia = 0.72;
    this.thirdPerson.fov = 0.9;
    this.thirdPerson.minZ = 0.08;
    this.thirdPerson.checkCollisions = true;
    this.thirdPerson.collisionRadius = new Vector3(0.28, 0.28, 0.28);

    this.firstPerson = new UniversalCamera(
      'first-person-camera',
      new Vector3(0, PLAYER_EYE_HEIGHT, 0),
      scene,
    );
    // Babylon's camera keyboard movement is intentionally disabled. WASD is
    // converted to an explicit server-authoritative player command elsewhere.
    this.firstPerson.keysUp = [];
    this.firstPerson.keysDown = [];
    this.firstPerson.keysLeft = [];
    this.firstPerson.keysRight = [];
    this.firstPerson.speed = 0;
    this.firstPerson.angularSensibility = 2400;
    this.firstPerson.inertia = 0.45;
    this.firstPerson.fov = 1.08;
    this.firstPerson.minZ = 0.04;

    scene.activeCamera = this.thirdPerson;
    this.thirdPerson.attachControl(canvas, true);
  }

  getMode(): PlayerCameraMode {
    return this.mode;
  }

  setMode(mode: PlayerCameraMode, playerPosition?: Vector3, playerHeading = 0): void {
    if (mode === this.mode && this.scene.activeCamera === this.activeCamera()) return;
    const previousMode = this.mode;
    this.activeCamera().detachControl();
    this.mode = mode;

    if (mode === 'first-person') {
      if (playerPosition) this.firstPerson.position.copyFrom(playerPosition.add(new Vector3(0, PLAYER_EYE_HEIGHT, 0)));
      this.firstPerson.rotation.x = 0;
      this.firstPerson.rotation.y = playerHeading;
      this.firstPerson.rotation.z = 0;
      this.firstPersonHeadingInitialized = Boolean(playerPosition);
      this.scene.activeCamera = this.firstPerson;
      this.firstPerson.attachControl(this.canvas, true);
      return;
    }

    const viewHeading = previousMode === 'first-person' ? this.firstPerson.rotation.y : playerHeading;
    if (playerPosition) this.thirdPerson.target.copyFrom(playerPosition.add(new Vector3(0, THIRD_PERSON_TARGET_HEIGHT, 0)));
    this.thirdPerson.alpha = -Math.PI / 2 - viewHeading;
    this.thirdPerson.beta = 1.05;
    this.thirdPerson.radius = THIRD_PERSON_DISTANCE;
    this.scene.activeCamera = this.thirdPerson;
    this.thirdPerson.attachControl(this.canvas, true);
  }

  update(playerPosition: Vector3 | undefined, playerHeading: number, dt: number, followThirdPerson: boolean): void {
    if (!playerPosition) return;
    if (this.mode === 'first-person') {
      this.firstPerson.position.copyFrom(playerPosition.add(new Vector3(0, PLAYER_EYE_HEIGHT, 0)));
      if (!this.firstPersonHeadingInitialized) {
        this.firstPerson.rotation.y = playerHeading;
        this.firstPersonHeadingInitialized = true;
      }
      this.firstPerson.rotation.x = Math.max(-1.35, Math.min(1.35, this.firstPerson.rotation.x));
      return;
    }
    if (followThirdPerson) {
      const target = playerPosition.add(new Vector3(0, THIRD_PERSON_TARGET_HEIGHT, 0));
      this.thirdPerson.target.copyFrom(Vector3.Lerp(
        this.thirdPerson.target,
        target,
        1 - Math.exp(-7 * dt),
      ));
    }
    if (!Number.isFinite(this.thirdPerson.alpha)) this.thirdPerson.alpha = -Math.PI / 2 - playerHeading;
  }

  /** rightInput: A/D axis; forwardInput: S/W axis. Result uses Babylon X/Z ground axes. */
  cameraRelativeGroundDirection(rightInput: number, forwardInput: number): Vector3 {
    if (!rightInput && !forwardInput) return Vector3.Zero();
    const rayForward = this.activeCamera().getForwardRay().direction;
    const forward = new Vector3(rayForward.x, 0, rayForward.z);
    if (forward.lengthSquared() > 1e-6) {
      forward.normalize();
      this.lastPlanarForward.copyFrom(forward);
    } else {
      forward.copyFrom(this.lastPlanarForward);
    }
    const right = new Vector3(forward.z, 0, -forward.x);
    return right.scale(rightInput).addInPlace(forward.scale(forwardInput)).normalize();
  }

  dispose(): void {
    this.thirdPerson.detachControl();
    this.firstPerson.detachControl();
  }

  private activeCamera(): ArcRotateCamera | UniversalCamera {
    return this.mode === 'first-person' ? this.firstPerson : this.thirdPerson;
  }
}
