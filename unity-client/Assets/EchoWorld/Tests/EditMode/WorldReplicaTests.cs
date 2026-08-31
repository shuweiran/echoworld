using System.Collections.Generic;
using EchoWorld.Client.Protocol;
using EchoWorld.Client.Replica;
using Newtonsoft.Json.Linq;
using NUnit.Framework;

namespace EchoWorld.Client.Tests
{
    public sealed class WorldReplicaTests
    {
        [Test]
        public void FullSnapshotThenDelta_AppliesWrappedCreateUpdateAndRemove()
        {
            var replica = new WorldReplica();
            var full = Frame(0, true);
            full.Creates.Add(Create(Entity("agent-1", 1f)));

            var fullResult = replica.Apply(full);

            Assert.That(fullResult.Status, Is.EqualTo(ReplicaApplyStatus.Applied));
            Assert.That(replica.EntityCount, Is.EqualTo(1));

            var delta = Frame(1, false);
            delta.Updates.Add(Update(Entity("agent-1", 4f)));
            delta.Creates.Add(Create(Entity("chair-1", 8f, "WORLD_OBJECT")));

            var deltaResult = replica.Apply(delta);

            Assert.That(deltaResult.Status, Is.EqualTo(ReplicaApplyStatus.Applied));
            Assert.That(replica.EntityCount, Is.EqualTo(2));
            Assert.That(replica.TryGetEntity("agent-1", out var agent), Is.True);
            Assert.That((float)agent.State["x"], Is.EqualTo(4f));

            var remove = Frame(2, false);
            remove.Removes.Add(new ReplicationRemoveDto
            {
                EntityId = "chair-1",
                Revision = 2,
                Reason = "LEFT_INTEREST"
            });
            replica.Apply(remove);

            Assert.That(replica.EntityCount, Is.EqualTo(1));
            Assert.That(replica.TryGetEntity("chair-1", out _), Is.False);
        }

        [Test]
        public void SequenceGap_DoesNotMutateReplicaAndRequiresReplay()
        {
            var replica = new WorldReplica();
            var first = Frame(0, true);
            first.Creates.Add(Create(Entity("agent-1", 1f)));
            replica.Apply(first);

            var gap = Frame(2, false);
            gap.Updates.Add(Update(Entity("agent-1", 99f)));

            var result = replica.Apply(gap);

            Assert.That(result.Status, Is.EqualTo(ReplicaApplyStatus.SequenceGap));
            Assert.That(result.RequiresReplay, Is.True);
            Assert.That(replica.LastSequence, Is.EqualTo(0));
            Assert.That(replica.TryGetEntity("agent-1", out var unchanged), Is.True);
            Assert.That((float)unchanged.State["x"], Is.EqualTo(1f));
        }

        [Test]
        public void DeltaBeforeJoinSnapshot_IsRejected()
        {
            var replica = new WorldReplica();

            var result = replica.Apply(Frame(0, false));

            Assert.That(result.Status, Is.EqualTo(ReplicaApplyStatus.SequenceGap));
            Assert.That(replica.LastSequence, Is.EqualTo(-1));
        }

        [Test]
        public void UnknownUpdate_IsRejectedAtomically()
        {
            var replica = new WorldReplica();
            var first = Frame(0, true);
            first.Creates.Add(Create(Entity("known", 1f)));
            replica.Apply(first);

            var invalid = Frame(1, false);
            invalid.Updates.Add(Update(Entity("known", 2f)));
            invalid.Updates.Add(Update(Entity("missing", 3f)));

            var result = replica.Apply(invalid);

            Assert.That(result.Status, Is.EqualTo(ReplicaApplyStatus.SequenceGap));
            Assert.That(replica.TryGetEntity("known", out var known), Is.True);
            Assert.That((float)known.State["x"], Is.EqualTo(1f));
        }

        [Test]
        public void OlderFrame_IsIdempotentlyIgnored()
        {
            var replica = new WorldReplica();
            replica.Apply(Frame(0, true));

            var result = replica.Apply(Frame(0, false));

            Assert.That(result.Status, Is.EqualTo(ReplicaApplyStatus.IgnoredStale));
            Assert.That(result.RequiresReplay, Is.False);
        }

        [Test]
        public void ReturnedEntity_IsADefensiveCopy()
        {
            var replica = new WorldReplica();
            var frame = Frame(0, true);
            frame.Creates.Add(Create(Entity("agent-1", 2f)));
            replica.Apply(frame);

            replica.TryGetEntity("agent-1", out var external);
            external.State["x"] = 200f;

            replica.TryGetEntity("agent-1", out var stored);
            Assert.That((float)stored.State["x"], Is.EqualTo(2f));
        }

        private static ReplicationFrameDto Frame(long sequence, bool full)
        {
            return new ReplicationFrameDto
            {
                ProtocolVersion = ProtocolConstants.CurrentVersion,
                Sequence = sequence,
                ServerTick = sequence * 2,
                ServerTimeEpochMillis = 1_700_000_000_000L + sequence,
                FullSnapshot = full,
                Creates = new List<ReplicationCreateDto>(),
                Updates = new List<ReplicationUpdateDto>(),
                Removes = new List<ReplicationRemoveDto>(),
                Events = new List<ReplicationEventDto>()
            };
        }

        private static ReplicationCreateDto Create(ReplicaEntityDto entity)
        {
            return new ReplicationCreateDto { Entity = entity };
        }

        private static ReplicationUpdateDto Update(ReplicaEntityDto entity)
        {
            return new ReplicationUpdateDto { Entity = entity };
        }

        private static ReplicaEntityDto Entity(string id, float x, string type = "AGENT")
        {
            return new ReplicaEntityDto
            {
                EntityId = id,
                EntityType = type,
                Revision = 1,
                Cell = new SpatialCellDto { ZoneId = "world", FloorId = "ground" },
                PerceptionScope = new PerceptionScopeDto { PublicToAll = true },
                State = JObject.FromObject(new { x, y = 0f, locomotionState = "IDLE" })
            };
        }
    }
}

