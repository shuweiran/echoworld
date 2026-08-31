using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.AddressableAssets;
using UnityEngine.ResourceManagement.AsyncOperations;

namespace EchoWorld.Client.Assets
{
    /// <summary>
    /// Presentation-only assetId mapping. Prefab addresses never enter the server contract.
    /// </summary>
    public sealed class AddressablesAssetResolver : MonoBehaviour, IAssetPresentationResolver
    {
        [Serializable]
        private sealed class Entry
        {
            public string AssetId;
            public AssetReferenceGameObject Prefab;
        }

        [SerializeField]
        private List<Entry> entries = new List<Entry>();

        [SerializeField]
        private bool useAssetIdAsAddress = true;

        private Dictionary<string, AssetReferenceGameObject> _catalog;

        public bool CanResolve(string assetId)
        {
            EnsureCatalog();
            return !string.IsNullOrWhiteSpace(assetId)
                && (useAssetIdAsAddress || _catalog.ContainsKey(assetId));
        }

        public async Task<PresentationAssetLease> InstantiateAsync(
            string assetId,
            Transform parent,
            CancellationToken cancellationToken)
        {
            EnsureCatalog();
            if (string.IsNullOrWhiteSpace(assetId))
            {
                throw new KeyNotFoundException("assetId is required for Addressables resolution.");
            }

            cancellationToken.ThrowIfCancellationRequested();
            UnityEngine.ResourceManagement.AsyncOperations.AsyncOperationHandle<GameObject> handle;
            if (_catalog.TryGetValue(assetId, out var reference) && reference != null)
            {
                handle = reference.InstantiateAsync(parent);
            }
            else if (useAssetIdAsAddress)
            {
                handle = Addressables.InstantiateAsync(assetId, parent);
            }
            else
            {
                throw new KeyNotFoundException($"No Addressables presentation is registered for assetId '{assetId}'.");
            }

            var instance = await handle.Task;
            if (handle.Status != AsyncOperationStatus.Succeeded || instance == null)
            {
                if (handle.IsValid())
                {
                    Addressables.Release(handle);
                }

                throw new InvalidOperationException($"Addressables failed to instantiate assetId '{assetId}'.");
            }

            if (cancellationToken.IsCancellationRequested)
            {
                Addressables.ReleaseInstance(instance);
                cancellationToken.ThrowIfCancellationRequested();
            }

            return new PresentationAssetLease(instance, value => Addressables.ReleaseInstance(value));
        }

        private void EnsureCatalog()
        {
            if (_catalog != null)
            {
                return;
            }

            _catalog = new Dictionary<string, AssetReferenceGameObject>(StringComparer.Ordinal);
            foreach (var entry in entries)
            {
                if (entry == null || string.IsNullOrWhiteSpace(entry.AssetId) || entry.Prefab == null)
                {
                    continue;
                }

                if (_catalog.ContainsKey(entry.AssetId))
                {
                    throw new InvalidOperationException($"Duplicate Addressables assetId '{entry.AssetId}'.");
                }

                _catalog.Add(entry.AssetId, entry.Prefab);
            }
        }
    }
}
