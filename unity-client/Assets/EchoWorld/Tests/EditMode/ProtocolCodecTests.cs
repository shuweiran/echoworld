using EchoWorld.Client.Protocol;
using NUnit.Framework;

namespace EchoWorld.Client.Tests
{
    public sealed class ProtocolCodecTests
    {
        [Test]
        public void Hello_RoundTripsAsHandlerEnvelope()
        {
            var hello = new HelloDto
            {
                ClientId = "unity-test",
                ProtocolVersion = 1,
                FocusCell = new SpatialCellDto
                {
                    ZoneId = "world",
                    FloorId = "ground",
                    X = 2,
                    Z = 3
                },
                RadiusCells = 4
            };

            var json = ProtocolCodec.Encode(ProtocolConstants.Hello, hello);
            var envelope = ProtocolCodec.DecodeEnvelope(json);
            var decoded = ProtocolCodec.DecodePayload<HelloDto>(envelope);

            Assert.That(envelope.Type, Is.EqualTo("hello"));
            Assert.That(decoded.ClientId, Is.EqualTo("unity-test"));
            Assert.That(decoded.ProtocolVersion, Is.EqualTo(1));
            Assert.That(decoded.FocusCell.ZoneId, Is.EqualTo("world"));
            Assert.That(decoded.RadiusCells, Is.EqualTo(4));
            StringAssert.DoesNotContain("requestId", json);
        }

        [Test]
        public void ReplicationFrame_DecodesJavaWrapperShape()
        {
            const string json = "{\"type\":\"replication_frame\",\"payload\":{" +
                                "\"protocolVersion\":1,\"sequence\":1,\"serverTick\":9," +
                                "\"serverTimeEpochMillis\":1700000000000," +
                                "\"creates\":[{\"entity\":{\"entityId\":\"a\",\"entityType\":\"AGENT\"," +
                                "\"revision\":9,\"cell\":{\"zoneId\":\"world\",\"floorId\":\"ground\",\"x\":0,\"z\":0}," +
                                "\"ownerClientId\":\"\",\"perceptionScope\":{\"publicToAll\":true,\"clientIds\":[]}," +
                                "\"narrativeTags\":[],\"state\":{\"x\":1,\"y\":2}}}]," +
                                "\"updates\":[],\"removes\":[],\"events\":[]}}";

            var frame = ProtocolCodec.DecodePayload<ReplicationFrameDto>(ProtocolCodec.DecodeEnvelope(json));

            Assert.That(frame.ProtocolVersion, Is.EqualTo(1));
            Assert.That(frame.ServerTimeEpochMillis, Is.EqualTo(1_700_000_000_000L));
            Assert.That(frame.Creates[0].Entity.EntityId, Is.EqualTo("a"));
            Assert.That((int)frame.Creates[0].Entity.State["y"], Is.EqualTo(2));
        }

        [Test]
        public void ErrorWithoutPayload_IsAccepted()
        {
            var envelope = ProtocolCodec.DecodeEnvelope("{\"type\":\"error\",\"code\":\"UNKNOWN_MESSAGE\"}");

            Assert.That(envelope.Type, Is.EqualTo("error"));
            Assert.That(envelope.Code, Is.EqualTo("UNKNOWN_MESSAGE"));
        }

        [Test]
        public void InvalidJson_ThrowsProtocolException()
        {
            Assert.Throws<ProtocolException>(() => ProtocolCodec.DecodeEnvelope("{not-json"));
        }
    }
}
