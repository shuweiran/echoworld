using System;
using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;
using EchoWorld.Client.Protocol;
using UnityEngine;

namespace EchoWorld.Client.Networking
{
    public sealed class EchoWorldClient : MonoBehaviour
    {
        private const int MaxMessagesPerFrame = 128;

        private readonly ConcurrentQueue<string> _inbound = new ConcurrentQueue<string>();
        private CancellationTokenSource _lifetime;
        private IWebSocketTransport _transport;
        private Uri _endpoint;
        private bool _autoConnect;

        public event Action<ReplicationFrameDto> ReplicationFrameReceived;
        public event Action<AckResultDto> AckResultReceived;
        public event Action<string> ServerErrorReceived;
        public event Action<string> ProtocolWarning;
        public event Action<WebSocketState> ConnectionStateChanged;

        public WebSocketState State => _transport?.State ?? WebSocketState.None;

        public void Configure(string endpoint, bool autoConnect)
        {
            _endpoint = new Uri(endpoint, UriKind.Absolute);
            _autoConnect = autoConnect;
        }

        private async void Start()
        {
            if (_autoConnect)
            {
                await ConnectAsync();
            }
        }

        public async Task ConnectAsync()
        {
            if (_endpoint == null)
            {
                throw new InvalidOperationException("EchoWorldClient.Configure must be called before connecting.");
            }

            if (_transport != null && (_transport.State == WebSocketState.Open || _transport.State == WebSocketState.Connecting))
            {
                return;
            }

            _lifetime?.Cancel();
            _lifetime?.Dispose();
            _transport?.Dispose();

            _lifetime = new CancellationTokenSource();
            _transport = new ClientWebSocketTransport();
            ConnectionStateChanged?.Invoke(WebSocketState.Connecting);

            try
            {
                await _transport.ConnectAsync(_endpoint, _lifetime.Token);
                ConnectionStateChanged?.Invoke(_transport.State);
                _ = ReceiveLoopAsync(_lifetime.Token);
            }
            catch (Exception exception)
            {
                ProtocolWarning?.Invoke($"WebSocket connect failed: {exception.Message}");
                ConnectionStateChanged?.Invoke(_transport.State);
            }
        }

        public Task SendAsync<T>(string type, T payload)
        {
            if (_transport == null || _transport.State != WebSocketState.Open)
            {
                throw new InvalidOperationException("WebSocket is not connected.");
            }

            var json = ProtocolCodec.Encode(type, payload);
            return _transport.SendTextAsync(json, _lifetime.Token);
        }

        private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
        {
            try
            {
                while (!cancellationToken.IsCancellationRequested && _transport.State == WebSocketState.Open)
                {
                    var message = await _transport.ReceiveTextAsync(cancellationToken);
                    if (message == null)
                    {
                        break;
                    }

                    _inbound.Enqueue(message);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception exception)
            {
                ProtocolWarning?.Invoke($"WebSocket receive failed: {exception.Message}");
            }
        }

        private void Update()
        {
            var processed = 0;
            while (processed++ < MaxMessagesPerFrame && _inbound.TryDequeue(out var json))
            {
                try
                {
                    Dispatch(json);
                }
                catch (Exception exception)
                {
                    ProtocolWarning?.Invoke(exception.Message);
                }
            }
        }

        private void Dispatch(string json)
        {
            var envelope = ProtocolCodec.DecodeEnvelope(json);
            switch (envelope.Type)
            {
                case ProtocolConstants.ReplicationFrame:
                    var frame = ProtocolCodec.DecodePayload<ReplicationFrameDto>(envelope);
                    ReplicationFrameReceived?.Invoke(frame);
                    break;
                case ProtocolConstants.FullSnapshot:
                    var snapshot = ProtocolCodec.DecodePayload<FullSnapshotDto>(envelope);
                    ReplicationFrameReceived?.Invoke(snapshot.ToFrame());
                    break;
                case ProtocolConstants.AckResult:
                    AckResultReceived?.Invoke(ProtocolCodec.DecodePayload<AckResultDto>(envelope));
                    break;
                case ProtocolConstants.Error:
                    ServerErrorReceived?.Invoke(envelope.Code ?? "UNKNOWN_SERVER_ERROR");
                    break;
                default:
                    ProtocolWarning?.Invoke($"Unknown server message type '{envelope.Type}'.");
                    break;
            }
        }

        private async void OnDestroy()
        {
            if (_lifetime != null)
            {
                _lifetime.Cancel();
            }

            if (_transport != null)
            {
                try
                {
                    using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(1)))
                    {
                        await _transport.CloseAsync(timeout.Token);
                    }
                }
                catch
                {
                    // Object teardown must not surface a transport exception into Unity's player loop.
                }

                _transport.Dispose();
            }

            _lifetime?.Dispose();
        }
    }
}
