using System;
using System.Collections.Generic;
using System.Threading;
using EchoWorld.Client.Assets;
using EchoWorld.Client.Protocol;
using EchoWorld.Client.Replica;
using UnityEngine;

namespace EchoWorld.Client.Presentation
{
    public sealed class WorldPresentationController : MonoBehaviour
    {
        private readonly Dictionary<string, EntityView> _views = new Dictionary<string, EntityView>(StringComparer.Ordinal);
        private readonly Dictionary<string, PresentationAssetLease> _leases = new Dictionary<string, PresentationAssetLease>(StringComparer.Ordinal);
        private readonly HashSet<string> _pending = new HashSet<string>(StringComparer.Ordinal);

        private WorldReplicaHost _host;
        private IAssetPresentationResolver _assetResolver;
        private CancellationTokenSource _lifetime;

        public void Initialize(WorldReplicaHost host, IAssetPresentationResolver assetResolver)
        {
            _host = host ?? throw new ArgumentNullException(nameof(host));
            _assetResolver = assetResolver;
            _lifetime = new CancellationTokenSource();
            _host.ReplicaChanged += OnReplicaChanged;
        }

        private void OnReplicaChanged(ReplicaApplyResult result)
        {
            Reconcile();
        }

        private void Reconcile()
        {
            var states = _host.Replica.SnapshotEntities();
            var present = new HashSet<string>(StringComparer.Ordinal);
            foreach (var state in states)
            {
                present.Add(state.EntityId);
                if (_views.TryGetValue(state.EntityId, out var view))
                {
                    view.Apply(state);
                }
                else if (!_pending.Contains(state.EntityId))
                {
                    CreateViewAsync(state);
                }
            }

            var removed = new List<string>();
            foreach (var entityId in _views.Keys)
            {
                if (!present.Contains(entityId))
                {
                    removed.Add(entityId);
                }
            }

            foreach (var entityId in removed)
            {
                ReleaseView(entityId);
            }
        }

        private async void CreateViewAsync(ReplicaEntityDto initialState)
        {
            _pending.Add(initialState.EntityId);
            PresentationAssetLease lease = null;
            try
            {
                var assetId = ReplicaPresentationProjection.AssetId(initialState);
                if (_assetResolver != null && _assetResolver.CanResolve(assetId))
                {
                    try
                    {
                        lease = await _assetResolver.InstantiateAsync(assetId, transform, _lifetime.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        throw;
                    }
                    catch (Exception exception)
                    {
                        Debug.LogWarning(
                            $"Addressables assetId '{assetId}' failed; using primitive fallback: {exception.Message}");
                        lease = CreateFallback(initialState);
                    }
                }
                else
                {
                    lease = CreateFallback(initialState);
                }

                if (!_host.Replica.TryGetEntity(initialState.EntityId, out var latest))
                {
                    lease.Dispose();
                    return;
                }

                var instance = lease.Instance;
                instance.name = $"{latest.EntityType ?? "entity"}:{latest.EntityId}";
                var view = instance.GetComponent<EntityView>();
                if (view == null)
                {
                    view = instance.AddComponent<EntityView>();
                }

                view.Initialize(latest.EntityId);
                view.Apply(latest);
                _leases.Add(latest.EntityId, lease);
                _views.Add(latest.EntityId, view);
            }
            catch (OperationCanceledException)
            {
                lease?.Dispose();
            }
            catch (Exception exception)
            {
                lease?.Dispose();
                Debug.LogWarning($"EchoWorld presentation creation failed for '{initialState.EntityId}': {exception.Message}");
            }
            finally
            {
                _pending.Remove(initialState.EntityId);
            }
        }

        private PresentationAssetLease CreateFallback(ReplicaEntityDto state)
        {
            var primitive = string.Equals(state.EntityType, "AGENT", StringComparison.OrdinalIgnoreCase)
                || string.Equals(state.EntityType, "PLAYER", StringComparison.OrdinalIgnoreCase)
                ? PrimitiveType.Capsule
                : PrimitiveType.Cube;
            var instance = GameObject.CreatePrimitive(primitive);
            instance.transform.SetParent(transform, false);
            return new PresentationAssetLease(instance, value => Destroy(value));
        }

        private void ReleaseView(string entityId)
        {
            _views.Remove(entityId);
            if (_leases.TryGetValue(entityId, out var lease))
            {
                _leases.Remove(entityId);
                lease.Dispose();
            }
        }

        private void OnDestroy()
        {
            if (_host != null)
            {
                _host.ReplicaChanged -= OnReplicaChanged;
            }

            _lifetime?.Cancel();
            _lifetime?.Dispose();
            foreach (var lease in _leases.Values)
            {
                lease.Dispose();
            }

            _leases.Clear();
            _views.Clear();
        }
    }
}
