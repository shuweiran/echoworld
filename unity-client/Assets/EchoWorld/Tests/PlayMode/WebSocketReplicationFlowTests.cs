using System;
using System.Collections;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using EchoWorld.Client.Commands;
using EchoWorld.Client.Networking;
using EchoWorld.Client.Protocol;
using EchoWorld.Client.Replica;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;

namespace EchoWorld.Client.Tests.PlayMode
{
    public sealed class WebSocketReplicationFlowTests
    {
        private const float TimeoutSeconds = 10f;

        [UnityTest]
        [Timeout(20000)]
        public IEnumerator LoopbackWebSocket_HelloSnapshotAndAckFlowThroughRuntimeComponents()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;

            var acceptTcp = listener.AcceptTcpClientAsync();
            var root = new GameObject("EchoWorld.PlayMode.Integration");
            var client = root.AddComponent<EchoWorldClient>();
            client.Configure($"ws://127.0.0.1:{port}/ws/", false);

            var commands = root.AddComponent<WorldReplicationCommandSender>();
            commands.Initialize(client, "unity-playmode", new InterestDto
            {
                FocusCell = new SpatialCellDto
                {
                    ZoneId = "world",
                    FloorId = "ground",
                    X = 2,
                    Z = 3
                },
                RadiusCells = 4
            });

            var replicaHost = root.AddComponent<WorldReplicaHost>();
            replicaHost.Initialize(client, commands);

            TcpClient serverClient = null;
            try
            {
                var connect = client.ConnectAsync();
                yield return WaitFor(acceptTcp, "TCP accept");
                AssertTaskSucceeded(acceptTcp);
                serverClient = acceptTcp.Result;
                var stream = serverClient.GetStream();

                var acceptWebSocket = AcceptWebSocketHandshakeAsync(stream, CancellationToken.None);
                yield return WaitFor(acceptWebSocket, "WebSocket upgrade");
                AssertTaskSucceeded(acceptWebSocket);

                yield return WaitFor(connect, "Unity ClientWebSocket connect");
                AssertTaskSucceeded(connect);
                Assert.That(client.State, Is.EqualTo(WebSocketState.Open));

                var receiveHello = ReceiveClientTextFrameAsync(stream, CancellationToken.None);
                yield return WaitFor(receiveHello, "hello message");
                AssertTaskSucceeded(receiveHello);

                var helloEnvelope = ProtocolCodec.DecodeEnvelope(receiveHello.Result);
                var hello = ProtocolCodec.DecodePayload<HelloDto>(helloEnvelope);
                Assert.That(helloEnvelope.Type, Is.EqualTo(ProtocolConstants.Hello));
                Assert.That(hello.ClientId, Is.EqualTo("unity-playmode"));
                Assert.That(hello.ProtocolVersion, Is.EqualTo(ProtocolConstants.CurrentVersion));
                Assert.That(hello.FocusCell.FloorId, Is.EqualTo("ground"));
                Assert.That(hello.RadiusCells, Is.EqualTo(4));

                const string fullSnapshot = "{\"type\":\"full_snapshot\",\"payload\":{"
                    + "\"protocolVersion\":1,\"sequence\":0,\"serverTick\":42,"
                    + "\"serverTimeEpochMillis\":1700000000042,\"entities\":[{"
                    + "\"entityId\":\"agent-1\",\"entityType\":\"AGENT\",\"revision\":1,"
                    + "\"cell\":{\"zoneId\":\"world\",\"floorId\":\"ground\",\"x\":2,\"z\":3},"
                    + "\"ownerClientId\":\"unity-playmode\","
                    + "\"perceptionScope\":{\"publicToAll\":true,\"clientIds\":[]},"
                    + "\"narrativeTags\":[],\"state\":{\"x\":5.0,\"y\":7.0,\"locomotionState\":\"IDLE\"}}],"
                    + "\"events\":[]}}";
                var sendSnapshot = SendServerTextFrameAsync(stream, fullSnapshot, CancellationToken.None);
                yield return WaitFor(sendSnapshot, "full_snapshot send");
                AssertTaskSucceeded(sendSnapshot);

                var replicaDeadline = Time.realtimeSinceStartup + TimeoutSeconds;
                yield return new WaitUntil(() =>
                    replicaHost.Replica.LastSequence == 0 || Time.realtimeSinceStartup >= replicaDeadline);
                Assert.That(replicaHost.Replica.LastSequence, Is.EqualTo(0));
                Assert.That(replicaHost.Replica.ServerTick, Is.EqualTo(42));
                Assert.That(replicaHost.Replica.EntityCount, Is.EqualTo(1));
                Assert.That(replicaHost.Replica.TryGetEntity("agent-1", out var entity), Is.True);
                Assert.That((float)entity.State["x"], Is.EqualTo(5f));

                var receiveAck = ReceiveClientTextFrameAsync(stream, CancellationToken.None);
                yield return WaitFor(receiveAck, "ack message");
                AssertTaskSucceeded(receiveAck);
                var ackEnvelope = ProtocolCodec.DecodeEnvelope(receiveAck.Result);
                var ack = ProtocolCodec.DecodePayload<SequenceDto>(ackEnvelope);
                Assert.That(ackEnvelope.Type, Is.EqualTo(ProtocolConstants.Ack));
                Assert.That(ack.Sequence, Is.EqualTo(0));
            }
            finally
            {
                serverClient?.Close();
                listener.Stop();
                UnityEngine.Object.Destroy(root);
            }

            yield return null;
        }

        private static IEnumerator WaitFor(Task task, string operation)
        {
            var deadline = Time.realtimeSinceStartup + TimeoutSeconds;
            yield return new WaitUntil(() => task.IsCompleted || Time.realtimeSinceStartup >= deadline);
            Assert.That(task.IsCompleted, Is.True, $"Timed out waiting for {operation}.");
        }

        private static void AssertTaskSucceeded(Task task)
        {
            if (task.IsCanceled)
            {
                Assert.Fail("Asynchronous operation was canceled.");
            }

            if (task.IsFaulted)
            {
                Assert.Fail(task.Exception?.GetBaseException().ToString());
            }
        }

        private static async Task AcceptWebSocketHandshakeAsync(
            NetworkStream stream,
            CancellationToken cancellationToken)
        {
            var requestBytes = new MemoryStream();
            var terminator = new byte[] { 13, 10, 13, 10 };
            var matched = 0;
            while (requestBytes.Length < 16 * 1024)
            {
                var one = new byte[1];
                await ReadExactlyAsync(stream, one, 0, 1, cancellationToken);
                requestBytes.WriteByte(one[0]);
                matched = one[0] == terminator[matched] ? matched + 1 : (one[0] == terminator[0] ? 1 : 0);
                if (matched == terminator.Length)
                {
                    break;
                }
            }

            Assert.That(matched, Is.EqualTo(terminator.Length), "Incomplete WebSocket HTTP handshake.");
            var request = Encoding.ASCII.GetString(requestBytes.ToArray());
            string clientKey = null;
            foreach (var line in request.Split(new[] { "\r\n" }, StringSplitOptions.RemoveEmptyEntries))
            {
                if (line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase))
                {
                    clientKey = line.Substring(line.IndexOf(':') + 1).Trim();
                    break;
                }
            }

            Assert.That(clientKey, Is.Not.Null.And.Not.Empty);
            string accept;
            using (var sha1 = SHA1.Create())
            {
                var source = Encoding.ASCII.GetBytes(clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
                accept = Convert.ToBase64String(sha1.ComputeHash(source));
            }

            var response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + $"Sec-WebSocket-Accept: {accept}\r\n\r\n";
            var responseBytes = Encoding.ASCII.GetBytes(response);
            await stream.WriteAsync(responseBytes, 0, responseBytes.Length, cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }

        private static async Task<string> ReceiveClientTextFrameAsync(
            NetworkStream stream,
            CancellationToken cancellationToken)
        {
            var header = new byte[2];
            await ReadExactlyAsync(stream, header, 0, header.Length, cancellationToken);
            Assert.That(header[0] & 0x0F, Is.EqualTo(0x01), "Expected a text WebSocket frame.");
            Assert.That(header[1] & 0x80, Is.Not.Zero, "Client WebSocket frames must be masked.");

            ulong payloadLength = (uint)(header[1] & 0x7F);
            if (payloadLength == 126)
            {
                var extended = new byte[2];
                await ReadExactlyAsync(stream, extended, 0, 2, cancellationToken);
                payloadLength = (uint)((extended[0] << 8) | extended[1]);
            }
            else if (payloadLength == 127)
            {
                var extended = new byte[8];
                await ReadExactlyAsync(stream, extended, 0, 8, cancellationToken);
                payloadLength = 0;
                for (var i = 0; i < extended.Length; i++)
                {
                    payloadLength = (payloadLength << 8) | extended[i];
                }
            }

            Assert.That(payloadLength, Is.LessThanOrEqualTo(64 * 1024));
            var mask = new byte[4];
            await ReadExactlyAsync(stream, mask, 0, mask.Length, cancellationToken);
            var payload = new byte[(int)payloadLength];
            await ReadExactlyAsync(stream, payload, 0, payload.Length, cancellationToken);
            for (var i = 0; i < payload.Length; i++)
            {
                payload[i] ^= mask[i % mask.Length];
            }

            return Encoding.UTF8.GetString(payload);
        }

        private static async Task SendServerTextFrameAsync(
            NetworkStream stream,
            string text,
            CancellationToken cancellationToken)
        {
            var payload = Encoding.UTF8.GetBytes(text);
            using (var frame = new MemoryStream())
            {
                frame.WriteByte(0x81);
                if (payload.Length <= 125)
                {
                    frame.WriteByte((byte)payload.Length);
                }
                else if (payload.Length <= ushort.MaxValue)
                {
                    frame.WriteByte(126);
                    frame.WriteByte((byte)(payload.Length >> 8));
                    frame.WriteByte((byte)payload.Length);
                }
                else
                {
                    frame.WriteByte(127);
                    var length = (ulong)payload.Length;
                    for (var shift = 56; shift >= 0; shift -= 8)
                    {
                        frame.WriteByte((byte)(length >> shift));
                    }
                }

                frame.Write(payload, 0, payload.Length);
                var bytes = frame.ToArray();
                await stream.WriteAsync(bytes, 0, bytes.Length, cancellationToken);
                await stream.FlushAsync(cancellationToken);
            }
        }

        private static async Task ReadExactlyAsync(
            Stream stream,
            byte[] buffer,
            int offset,
            int count,
            CancellationToken cancellationToken)
        {
            while (count > 0)
            {
                var read = await stream.ReadAsync(buffer, offset, count, cancellationToken);
                if (read == 0)
                {
                    throw new EndOfStreamException("WebSocket peer closed before the frame completed.");
                }

                offset += read;
                count -= read;
            }
        }
    }
}
