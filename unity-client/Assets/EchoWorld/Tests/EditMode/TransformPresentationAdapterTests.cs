using EchoWorld.Client.Presentation;
using EchoWorld.Client.Protocol;
using NUnit.Framework;
using UnityEngine;

namespace EchoWorld.Client.Tests
{
    public sealed class TransformPresentationAdapterTests
    {
        private GameObject _gameObject;
        private TransformPresentationAdapter _adapter;

        [SetUp]
        public void SetUp()
        {
            _gameObject = new GameObject("presentation-test");
            _adapter = _gameObject.AddComponent<TransformPresentationAdapter>();
        }

        [TearDown]
        public void TearDown()
        {
            Object.DestroyImmediate(_gameObject);
        }

        [Test]
        public void FirstReplicatedTransform_SnapsToKnownBaseline()
        {
            _adapter.ApplyAuthoritativeTarget(new WorldTransformDto
            {
                X = 2f,
                Y = 1f,
                Z = 3f,
                RotationW = 1f
            });

            Assert.That(_gameObject.transform.position, Is.EqualTo(new Vector3(2f, 1f, 3f)));
        }

        [Test]
        public void ZeroQuaternion_FallsBackToIdentity()
        {
            _adapter.ApplyAuthoritativeTarget(new WorldTransformDto());

            Assert.That(_adapter.TargetRotation, Is.EqualTo(Quaternion.identity));
            Assert.That(_gameObject.transform.rotation, Is.EqualTo(Quaternion.identity));
        }

        [Test]
        public void SubsequentTransform_IsSmoothedWithoutMutatingDto()
        {
            _adapter.ApplyAuthoritativeTarget(new WorldTransformDto { RotationW = 1f });
            var update = new WorldTransformDto { X = 4f, RotationW = 1f };

            _adapter.ApplyAuthoritativeTarget(update);
            _adapter.Advance(0.01f);

            Assert.That(_gameObject.transform.position.x, Is.GreaterThan(0f).And.LessThan(4f));
            Assert.That(update.X, Is.EqualTo(4f));
        }
    }
}
