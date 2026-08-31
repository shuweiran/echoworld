using EchoWorld.Client.Protocol;
using UnityEngine;

namespace EchoWorld.Client.Presentation
{
    /// <summary>
    /// Smooths a replicated target for rendering. The rendered Transform is never read back
    /// into WorldReplica and never sent as a player command.
    /// </summary>
    public sealed class TransformPresentationAdapter : MonoBehaviour
    {
        [SerializeField, Min(0.01f)]
        private float positionSharpness = 14f;

        [SerializeField, Min(0.01f)]
        private float rotationSharpness = 18f;

        [SerializeField, Min(0f)]
        private float teleportDistance = 8f;

        private Vector3 _targetPosition;
        private Quaternion _targetRotation = Quaternion.identity;
        private bool _hasTarget;

        public Vector3 TargetPosition => _targetPosition;

        public Quaternion TargetRotation => _targetRotation;

        public void ApplyAuthoritativeTarget(WorldTransformDto state)
        {
            if (state == null)
            {
                return;
            }

            _targetPosition = new Vector3(state.X, state.Y, state.Z);
            var rotation = new Quaternion(state.RotationX, state.RotationY, state.RotationZ, state.RotationW);
            _targetRotation = rotation.sqrMagnitude < 0.0001f ? Quaternion.identity : rotation.normalized;

            if (!_hasTarget || Vector3.Distance(transform.position, _targetPosition) > teleportDistance)
            {
                transform.SetPositionAndRotation(_targetPosition, _targetRotation);
            }

            _hasTarget = true;
        }

        private void LateUpdate()
        {
            Advance(Time.deltaTime);
        }

        public void Advance(float deltaTime)
        {
            if (!_hasTarget || deltaTime <= 0f)
            {
                return;
            }

            var positionAlpha = 1f - Mathf.Exp(-positionSharpness * deltaTime);
            var rotationAlpha = 1f - Mathf.Exp(-rotationSharpness * deltaTime);
            transform.position = Vector3.Lerp(transform.position, _targetPosition, positionAlpha);
            transform.rotation = Quaternion.Slerp(transform.rotation, _targetRotation, rotationAlpha);
        }
    }
}

