using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace EchoWorld.Client.Networking
{
    public sealed class ClientWebSocketTransport : IWebSocketTransport
    {
        private readonly ClientWebSocket _socket = new ClientWebSocket();
        private readonly SemaphoreSlim _sendGate = new SemaphoreSlim(1, 1);

        public WebSocketState State => _socket.State;

        public Task ConnectAsync(Uri endpoint, CancellationToken cancellationToken)
        {
            return _socket.ConnectAsync(endpoint, cancellationToken);
        }

        public async Task SendTextAsync(string message, CancellationToken cancellationToken)
        {
            var bytes = Encoding.UTF8.GetBytes(message);
            await _sendGate.WaitAsync(cancellationToken);
            try
            {
                await _socket.SendAsync(
                    new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Text,
                    true,
                    cancellationToken);
            }
            finally
            {
                _sendGate.Release();
            }
        }

        public async Task<string> ReceiveTextAsync(CancellationToken cancellationToken)
        {
            var buffer = new byte[8192];
            using (var stream = new MemoryStream())
            {
                while (true)
                {
                    var result = await _socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellationToken);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        return null;
                    }

                    if (result.MessageType != WebSocketMessageType.Text)
                    {
                        throw new WebSocketException("EchoWorld V2 currently accepts text JSON frames only.");
                    }

                    stream.Write(buffer, 0, result.Count);
                    if (result.EndOfMessage)
                    {
                        return Encoding.UTF8.GetString(stream.ToArray());
                    }
                }
            }
        }

        public async Task CloseAsync(CancellationToken cancellationToken)
        {
            if (_socket.State == WebSocketState.Open || _socket.State == WebSocketState.CloseReceived)
            {
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "client shutdown", cancellationToken);
            }
        }

        public void Dispose()
        {
            _sendGate.Dispose();
            _socket.Dispose();
        }
    }
}

