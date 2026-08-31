using System;
using EchoWorld.Client.Protocol;
using Newtonsoft.Json.Linq;

namespace EchoWorld.Client.Presentation
{
    /// <summary>Maps the Java ReplicaEntity.state bag into presentation-only typed hints.</summary>
    public static class ReplicaPresentationProjection
    {
        public static string AssetId(ReplicaEntityDto entity)
        {
            return entity?.State?.Value<string>("assetId");
        }

        public static WorldTransformDto Transform(ReplicaEntityDto entity)
        {
            var state = entity?.State;
            var transform = state?["transform"] as JObject;
            var position = transform?["position"] as JObject;
            var rotation = transform?["rotation"] as JObject;

            if (position != null)
            {
                return new WorldTransformDto
                {
                    X = Number(position, "x"),
                    Y = Number(position, "y"),
                    Z = Number(position, "z"),
                    RotationX = Number(rotation, "x"),
                    RotationY = Number(rotation, "y"),
                    RotationZ = Number(rotation, "z"),
                    RotationW = rotation == null ? 1f : Number(rotation, "w", 1f),
                    FloorId = entity.Cell?.FloorId
                };
            }

            // Current legacy projection exposes 2D x/y. In V2 ground space, legacy y maps to Z.
            return new WorldTransformDto
            {
                X = Number(state, "x"),
                Y = 0f,
                Z = Number(state, "y"),
                RotationW = 1f,
                FloorId = entity?.Cell?.FloorId
            };
        }

        public static PresentationStateDto Presentation(ReplicaEntityDto entity)
        {
            var state = entity?.State;
            var vx = Number(state, "vx");
            var vz = Number(state, "vy");
            return new PresentationStateDto
            {
                Locomotion = state?.Value<string>("locomotionState") ?? "IDLE",
                Speed = (float)Math.Sqrt(vx * vx + vz * vz),
                MoveX = vx,
                MoveZ = vz,
                Stance = state?.Value<string>("stance"),
                ActionType = state?.Value<string>("actionType"),
                ActionPhase = state?.Value<string>("actionPhase"),
                LookTargetEntityId = state?.Value<string>("lookTargetEntityId")
            };
        }

        private static float Number(JObject value, string field, float fallback = 0f)
        {
            var token = value?[field];
            return token == null || token.Type == JTokenType.Null ? fallback : token.Value<float>();
        }
    }
}

