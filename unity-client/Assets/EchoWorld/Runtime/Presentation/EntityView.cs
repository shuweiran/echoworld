using EchoWorld.Client.Protocol;
using UnityEngine;

namespace EchoWorld.Client.Presentation
{
    public sealed class EntityView : MonoBehaviour
    {
        private TransformPresentationAdapter _transformAdapter;
        private LocomotionPresenter _locomotionPresenter;

        public string EntityId { get; private set; }

        public void Initialize(string entityId)
        {
            EntityId = entityId;
            _transformAdapter = GetComponent<TransformPresentationAdapter>();
            if (_transformAdapter == null)
            {
                _transformAdapter = gameObject.AddComponent<TransformPresentationAdapter>();
            }

            _locomotionPresenter = GetComponent<LocomotionPresenter>();
            if (_locomotionPresenter == null)
            {
                _locomotionPresenter = gameObject.AddComponent<LocomotionPresenter>();
            }

            _locomotionPresenter.Configure(GetComponentInChildren<Animator>());
        }

        public void Apply(ReplicaEntityDto state)
        {
            if (state == null || state.EntityId != EntityId)
            {
                return;
            }

            _transformAdapter.ApplyAuthoritativeTarget(ReplicaPresentationProjection.Transform(state));
            _locomotionPresenter.Apply(ReplicaPresentationProjection.Presentation(state));
        }
    }
}
