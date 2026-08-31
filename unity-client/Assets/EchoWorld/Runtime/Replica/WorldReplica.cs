using System;
using System.Collections.Generic;
using EchoWorld.Client.Protocol;

namespace EchoWorld.Client.Replica
{
    /// <summary>
    /// Non-authoritative client projection of the Java World Runtime. It applies only
    /// contiguous server frames and never advances gameplay or validates actions.
    /// </summary>
    public sealed class WorldReplica
    {
        private Dictionary<string, ReplicaEntityDto> _entities =
            new Dictionary<string, ReplicaEntityDto>(StringComparer.Ordinal);

        public long LastSequence { get; private set; } = -1;

        public long ServerTick { get; private set; }

        public long ServerTimeEpochMillis { get; private set; }

        public int EntityCount => _entities.Count;

        public ReplicaApplyResult Apply(ReplicationFrameDto frame)
        {
            if (frame == null)
            {
                return ReplicaApplyResult.Invalid("Frame is null.");
            }

            if (frame.ProtocolVersion != ProtocolConstants.CurrentVersion)
            {
                return ReplicaApplyResult.Incompatible(
                    $"Expected protocol {ProtocolConstants.CurrentVersion}, received {frame.ProtocolVersion}.");
            }

            if (frame.Sequence <= LastSequence)
            {
                return ReplicaApplyResult.Ignored(
                    $"Frame {frame.Sequence} is not newer than {LastSequence}.");
            }

            if (!frame.FullSnapshot)
            {
                if (LastSequence < 0)
                {
                    return ReplicaApplyResult.Gap("A full_snapshot is required before delta frames.");
                }

                var expected = LastSequence + 1;
                if (frame.Sequence != expected)
                {
                    return ReplicaApplyResult.Gap(
                        $"Expected sequence {expected}, received {frame.Sequence}.");
                }
            }

            var next = frame.FullSnapshot
                ? new Dictionary<string, ReplicaEntityDto>(StringComparer.Ordinal)
                : CloneEntities(_entities);
            var changed = new HashSet<string>(StringComparer.Ordinal);

            foreach (var create in frame.Creates ?? new List<ReplicationCreateDto>())
            {
                var entity = create?.Entity;
                if (!HasEntityId(entity))
                {
                    return ReplicaApplyResult.Invalid("Create entry is missing entity.entityId.");
                }

                if (next.ContainsKey(entity.EntityId))
                {
                    return ReplicaApplyResult.Invalid($"Create entry duplicates entity '{entity.EntityId}'.");
                }

                next.Add(entity.EntityId, CloneEntity(entity));
                changed.Add(entity.EntityId);
            }

            foreach (var update in frame.Updates ?? new List<ReplicationUpdateDto>())
            {
                var entity = update?.Entity;
                if (!HasEntityId(entity))
                {
                    return ReplicaApplyResult.Invalid("Update entry is missing entity.entityId.");
                }

                if (!next.ContainsKey(entity.EntityId))
                {
                    return ReplicaApplyResult.Gap($"Update references unknown entity '{entity.EntityId}'.");
                }

                next[entity.EntityId] = CloneEntity(entity);
                changed.Add(entity.EntityId);
            }

            foreach (var remove in frame.Removes ?? new List<ReplicationRemoveDto>())
            {
                if (remove == null || string.IsNullOrWhiteSpace(remove.EntityId))
                {
                    return ReplicaApplyResult.Invalid("Remove entry is missing entityId.");
                }

                next.Remove(remove.EntityId);
                changed.Add(remove.EntityId);
            }

            _entities = next;
            LastSequence = frame.Sequence;
            ServerTick = frame.ServerTick;
            ServerTimeEpochMillis = frame.ServerTimeEpochMillis;
            return ReplicaApplyResult.Applied(changed);
        }

        public bool TryGetEntity(string entityId, out ReplicaEntityDto entity)
        {
            if (_entities.TryGetValue(entityId, out var stored))
            {
                entity = CloneEntity(stored);
                return true;
            }

            entity = null;
            return false;
        }

        public IReadOnlyList<ReplicaEntityDto> SnapshotEntities()
        {
            var snapshot = new List<ReplicaEntityDto>(_entities.Count);
            foreach (var entity in _entities.Values)
            {
                snapshot.Add(CloneEntity(entity));
            }

            return snapshot;
        }

        private static bool HasEntityId(ReplicaEntityDto entity)
        {
            return entity != null && !string.IsNullOrWhiteSpace(entity.EntityId);
        }

        private static Dictionary<string, ReplicaEntityDto> CloneEntities(
            Dictionary<string, ReplicaEntityDto> source)
        {
            var clone = new Dictionary<string, ReplicaEntityDto>(source.Count, StringComparer.Ordinal);
            foreach (var pair in source)
            {
                clone.Add(pair.Key, CloneEntity(pair.Value));
            }

            return clone;
        }

        private static ReplicaEntityDto CloneEntity(ReplicaEntityDto source)
        {
            return new ReplicaEntityDto
            {
                EntityId = source.EntityId,
                EntityType = source.EntityType,
                Revision = source.Revision,
                Cell = source.Cell == null ? null : new SpatialCellDto
                {
                    ZoneId = source.Cell.ZoneId,
                    FloorId = source.Cell.FloorId,
                    X = source.Cell.X,
                    Z = source.Cell.Z
                },
                OwnerClientId = source.OwnerClientId,
                PerceptionScope = source.PerceptionScope == null ? null : new PerceptionScopeDto
                {
                    PublicToAll = source.PerceptionScope.PublicToAll,
                    ClientIds = new List<string>(source.PerceptionScope.ClientIds ?? new List<string>())
                },
                NarrativeTags = new List<string>(source.NarrativeTags ?? new List<string>()),
                State = source.State == null ? null : (Newtonsoft.Json.Linq.JObject)source.State.DeepClone()
            };
        }
    }

    public enum ReplicaApplyStatus
    {
        Applied,
        IgnoredStale,
        SequenceGap,
        InvalidFrame,
        IncompatibleProtocol
    }

    public sealed class ReplicaApplyResult
    {
        private ReplicaApplyResult(
            ReplicaApplyStatus status,
            string reason,
            bool requiresReplay,
            IReadOnlyCollection<string> changedEntityIds)
        {
            Status = status;
            Reason = reason;
            RequiresReplay = requiresReplay;
            ChangedEntityIds = changedEntityIds ?? Array.Empty<string>();
        }

        public ReplicaApplyStatus Status { get; }

        public string Reason { get; }

        public bool RequiresReplay { get; }

        public IReadOnlyCollection<string> ChangedEntityIds { get; }

        public static ReplicaApplyResult Applied(IReadOnlyCollection<string> changed) =>
            new ReplicaApplyResult(ReplicaApplyStatus.Applied, null, false, changed);

        public static ReplicaApplyResult Ignored(string reason) =>
            new ReplicaApplyResult(ReplicaApplyStatus.IgnoredStale, reason, false, null);

        public static ReplicaApplyResult Gap(string reason) =>
            new ReplicaApplyResult(ReplicaApplyStatus.SequenceGap, reason, true, null);

        public static ReplicaApplyResult Invalid(string reason) =>
            new ReplicaApplyResult(ReplicaApplyStatus.InvalidFrame, reason, true, null);

        public static ReplicaApplyResult Incompatible(string reason) =>
            new ReplicaApplyResult(ReplicaApplyStatus.IncompatibleProtocol, reason, false, null);
    }
}
