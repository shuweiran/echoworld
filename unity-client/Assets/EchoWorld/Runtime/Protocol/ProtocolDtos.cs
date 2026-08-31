using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace EchoWorld.Client.Protocol
{
    [Serializable]
    public sealed class ProtocolEnvelopeDto
    {
        [JsonProperty("type", Required = Required.Always)]
        public string Type;

        [JsonProperty("payload", NullValueHandling = NullValueHandling.Ignore)]
        public JToken Payload;

        [JsonProperty("code", NullValueHandling = NullValueHandling.Ignore)]
        public string Code;
    }

    [Serializable]
    public sealed class ReplicationFrameDto
    {
        [JsonProperty("protocolVersion", Required = Required.Always)]
        public int ProtocolVersion;

        [JsonProperty("sequence", Required = Required.Always)]
        public long Sequence;

        [JsonProperty("serverTick")]
        public long ServerTick;

        [JsonProperty("serverTimeEpochMillis")]
        public long ServerTimeEpochMillis;

        [JsonProperty("creates")]
        public List<ReplicationCreateDto> Creates = new List<ReplicationCreateDto>();

        [JsonProperty("updates")]
        public List<ReplicationUpdateDto> Updates = new List<ReplicationUpdateDto>();

        [JsonProperty("removes")]
        public List<ReplicationRemoveDto> Removes = new List<ReplicationRemoveDto>();

        [JsonProperty("events")]
        public List<ReplicationEventDto> Events = new List<ReplicationEventDto>();

        [JsonIgnore]
        public bool FullSnapshot;
    }

    [Serializable]
    public sealed class FullSnapshotDto
    {
        [JsonProperty("protocolVersion", Required = Required.Always)]
        public int ProtocolVersion;

        [JsonProperty("sequence", Required = Required.Always)]
        public long Sequence;

        [JsonProperty("serverTick")]
        public long ServerTick;

        [JsonProperty("serverTimeEpochMillis")]
        public long ServerTimeEpochMillis;

        [JsonProperty("entities")]
        public List<ReplicaEntityDto> Entities = new List<ReplicaEntityDto>();

        [JsonProperty("events")]
        public List<ReplicationEventDto> Events = new List<ReplicationEventDto>();

        public ReplicationFrameDto ToFrame()
        {
            var creates = new List<ReplicationCreateDto>();
            foreach (var entity in Entities ?? new List<ReplicaEntityDto>())
            {
                creates.Add(new ReplicationCreateDto { Entity = entity });
            }

            return new ReplicationFrameDto
            {
                ProtocolVersion = ProtocolVersion,
                Sequence = Sequence,
                ServerTick = ServerTick,
                ServerTimeEpochMillis = ServerTimeEpochMillis,
                Creates = creates,
                Updates = new List<ReplicationUpdateDto>(),
                Removes = new List<ReplicationRemoveDto>(),
                Events = Events ?? new List<ReplicationEventDto>(),
                FullSnapshot = true
            };
        }
    }

    [Serializable]
    public sealed class ReplicationCreateDto
    {
        [JsonProperty("entity", Required = Required.Always)]
        public ReplicaEntityDto Entity;
    }

    [Serializable]
    public sealed class ReplicationUpdateDto
    {
        [JsonProperty("entity", Required = Required.Always)]
        public ReplicaEntityDto Entity;
    }

    [Serializable]
    public sealed class ReplicationRemoveDto
    {
        [JsonProperty("entityId", Required = Required.Always)]
        public string EntityId;

        [JsonProperty("revision")]
        public long Revision;

        [JsonProperty("reason")]
        public string Reason;
    }

    [Serializable]
    public sealed class ReplicaEntityDto
    {
        [JsonProperty("entityId", Required = Required.Always)]
        public string EntityId;

        [JsonProperty("entityType", Required = Required.Always)]
        public string EntityType;

        [JsonProperty("revision")]
        public long Revision;

        [JsonProperty("cell")]
        public SpatialCellDto Cell;

        [JsonProperty("ownerClientId")]
        public string OwnerClientId;

        [JsonProperty("perceptionScope")]
        public PerceptionScopeDto PerceptionScope;

        [JsonProperty("narrativeTags")]
        public List<string> NarrativeTags = new List<string>();

        [JsonProperty("state")]
        public JObject State = new JObject();
    }

    [Serializable]
    public sealed class SpatialCellDto
    {
        [JsonProperty("zoneId")]
        public string ZoneId;

        [JsonProperty("floorId")]
        public string FloorId;

        [JsonProperty("x")]
        public int X;

        [JsonProperty("z")]
        public int Z;
    }

    [Serializable]
    public sealed class PerceptionScopeDto
    {
        [JsonProperty("publicToAll")]
        public bool PublicToAll;

        [JsonProperty("clientIds")]
        public List<string> ClientIds = new List<string>();
    }

    [Serializable]
    public sealed class ReplicationEventDto
    {
        [JsonProperty("eventId")]
        public string EventId;

        [JsonProperty("eventType")]
        public string EventType;

        [JsonProperty("serverTick")]
        public long ServerTick;

        [JsonProperty("cell")]
        public SpatialCellDto Cell;

        [JsonProperty("globalInterest")]
        public bool GlobalInterest;

        [JsonProperty("perceptionScope")]
        public PerceptionScopeDto PerceptionScope;

        [JsonProperty("narrativeTags")]
        public List<string> NarrativeTags = new List<string>();

        [JsonProperty("payload")]
        public JObject Payload = new JObject();
    }

    [Serializable]
    public sealed class HelloDto
    {
        [JsonProperty("clientId", Required = Required.Always)]
        public string ClientId;

        [JsonProperty("protocolVersion")]
        public int ProtocolVersion = ProtocolConstants.CurrentVersion;

        [JsonProperty("focusCell", NullValueHandling = NullValueHandling.Ignore)]
        public SpatialCellDto FocusCell;

        [JsonProperty("radiusCells")]
        public int RadiusCells = 2;

        [JsonProperty("narrativeSubscriptions")]
        public List<string> NarrativeSubscriptions = new List<string>();
    }

    [Serializable]
    public sealed class InterestDto
    {
        [JsonProperty("focusCell", NullValueHandling = NullValueHandling.Ignore)]
        public SpatialCellDto FocusCell;

        [JsonProperty("radiusCells")]
        public int RadiusCells = 2;

        [JsonProperty("narrativeSubscriptions")]
        public List<string> NarrativeSubscriptions = new List<string>();
    }

    [Serializable]
    public sealed class SequenceDto
    {
        [JsonProperty("sequence")]
        public long Sequence;
    }

    [Serializable]
    public sealed class AckResultDto
    {
        [JsonProperty("status")]
        public string Status;

        [JsonProperty("highestAcknowledgedSequence")]
        public long HighestAcknowledgedSequence;

        [JsonProperty("latestSequence")]
        public long LatestSequence;
    }

    /// <summary>Presentation projection, not part of the Java replication wire DTO.</summary>
    public sealed class WorldTransformDto
    {
        public float X;
        public float Y;
        public float Z;
        public float RotationX;
        public float RotationY;
        public float RotationZ;
        public float RotationW = 1f;
        public string FloorId;
    }

    /// <summary>Presentation projection, not part of the Java replication wire DTO.</summary>
    public sealed class PresentationStateDto
    {
        public string Locomotion;
        public float Speed;
        public float MoveX;
        public float MoveZ;
        public string Stance;
        public string ActionType;
        public string ActionPhase;
        public string LookTargetEntityId;
    }
}
