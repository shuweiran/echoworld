using System;
using System.Threading;
using System.Threading.Tasks;
using UnityEngine;

namespace EchoWorld.Client.Assets
{
    public interface IAssetPresentationResolver
    {
        bool CanResolve(string assetId);

        Task<PresentationAssetLease> InstantiateAsync(
            string assetId,
            Transform parent,
            CancellationToken cancellationToken);
    }

    public sealed class PresentationAssetLease : IDisposable
    {
        private readonly Action<GameObject> _release;
        private bool _released;

        public PresentationAssetLease(GameObject instance, Action<GameObject> release)
        {
            Instance = instance ?? throw new ArgumentNullException(nameof(instance));
            _release = release ?? throw new ArgumentNullException(nameof(release));
        }

        public GameObject Instance { get; }

        public void Dispose()
        {
            if (_released)
            {
                return;
            }

            _released = true;
            if (Instance != null)
            {
                _release(Instance);
            }
        }
    }
}

