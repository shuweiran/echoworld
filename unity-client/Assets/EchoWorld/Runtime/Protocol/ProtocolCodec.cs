using System;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace EchoWorld.Client.Protocol
{
    public static class ProtocolCodec
    {
        private static readonly JsonSerializerSettings Settings = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Ignore,
            MissingMemberHandling = MissingMemberHandling.Ignore,
            DateParseHandling = DateParseHandling.None
        };

        public static string Encode<T>(string type, T payload)
        {
            if (string.IsNullOrWhiteSpace(type))
            {
                throw new ArgumentException("Message type is required.", nameof(type));
            }

            var envelope = new ProtocolEnvelopeDto
            {
                Type = type,
                Payload = payload == null ? JValue.CreateNull() : JToken.FromObject(payload)
            };
            return JsonConvert.SerializeObject(envelope, Formatting.None, Settings);
        }

        public static ProtocolEnvelopeDto DecodeEnvelope(string json)
        {
            if (string.IsNullOrWhiteSpace(json))
            {
                throw new ProtocolException("Cannot decode an empty message.");
            }

            ProtocolEnvelopeDto envelope;
            try
            {
                envelope = JsonConvert.DeserializeObject<ProtocolEnvelopeDto>(json, Settings);
            }
            catch (JsonException exception)
            {
                throw new ProtocolException("Invalid protocol JSON.", exception);
            }

            if (envelope == null || string.IsNullOrWhiteSpace(envelope.Type))
            {
                throw new ProtocolException("Envelope must contain type.");
            }

            return envelope;
        }

        public static T DecodePayload<T>(ProtocolEnvelopeDto envelope)
        {
            if (envelope == null || envelope.Payload == null || envelope.Payload.Type == JTokenType.Null)
            {
                throw new ProtocolException("Envelope payload is missing.");
            }

            try
            {
                return envelope.Payload.ToObject<T>();
            }
            catch (JsonException exception)
            {
                throw new ProtocolException($"Payload cannot be decoded as {typeof(T).Name}.", exception);
            }
        }
    }

    public sealed class ProtocolException : Exception
    {
        public ProtocolException(string message) : base(message)
        {
        }

        public ProtocolException(string message, Exception innerException) : base(message, innerException)
        {
        }
    }
}
