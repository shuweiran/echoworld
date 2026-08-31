using System;
using System.Collections.Generic;
using EchoWorld.Client.Protocol;
using UnityEngine;

namespace EchoWorld.Client.Presentation
{
    public sealed class LocomotionPresenter : MonoBehaviour
    {
        private static readonly int SpeedHash = Animator.StringToHash("Speed");
        private static readonly int MoveXHash = Animator.StringToHash("MoveX");
        private static readonly int MoveZHash = Animator.StringToHash("MoveZ");
        private static readonly int LocomotionHash = Animator.StringToHash("Locomotion");
        private static readonly int ActionCodeHash = Animator.StringToHash("ActionCode");
        private static readonly int ActionPhaseHash = Animator.StringToHash("ActionPhase");

        private Animator _animator;
        private HashSet<int> _parameters;

        private void Awake()
        {
            Configure(GetComponentInChildren<Animator>());
        }

        public void Configure(Animator animator)
        {
            _animator = animator;
            _parameters = new HashSet<int>();
            if (_animator == null)
            {
                return;
            }

            _animator.applyRootMotion = false;
            foreach (var parameter in _animator.parameters)
            {
                _parameters.Add(parameter.nameHash);
            }
        }

        public void Apply(PresentationStateDto state)
        {
            if (_animator == null || state == null)
            {
                return;
            }

            SetFloat(SpeedHash, Mathf.Max(0f, state.Speed));
            SetFloat(MoveXHash, state.MoveX);
            SetFloat(MoveZHash, state.MoveZ);
            SetInteger(LocomotionHash, LocomotionCode(state.Locomotion));
            SetInteger(ActionCodeHash, StableCode(state.ActionType));
            SetInteger(ActionPhaseHash, ActionPhaseCode(state.ActionPhase));
        }

        private void SetFloat(int hash, float value)
        {
            if (_parameters.Contains(hash))
            {
                _animator.SetFloat(hash, value);
            }
        }

        private void SetInteger(int hash, int value)
        {
            if (_parameters.Contains(hash))
            {
                _animator.SetInteger(hash, value);
            }
        }

        private static int LocomotionCode(string locomotion)
        {
            switch (locomotion?.Trim().ToUpperInvariant())
            {
                case "WALK": return 1;
                case "RUN": return 2;
                case "TURN": return 3;
                case "FALL": return 4;
                default: return 0;
            }
        }

        private static int ActionPhaseCode(string phase)
        {
            switch (phase?.Trim().ToUpperInvariant())
            {
                case "STARTING": return 1;
                case "EXECUTING": return 2;
                case "COMMITTING": return 3;
                case "SUCCEEDED": return 4;
                case "FAILED": return 5;
                case "CANCELLED": return 6;
                default: return 0;
            }
        }

        private static int StableCode(string value)
        {
            if (string.IsNullOrWhiteSpace(value))
            {
                return 0;
            }

            unchecked
            {
                var hash = 17;
                foreach (var character in value.ToUpperInvariant())
                {
                    hash = hash * 31 + character;
                }

                return Math.Abs(hash == int.MinValue ? int.MaxValue : hash);
            }
        }
    }
}

