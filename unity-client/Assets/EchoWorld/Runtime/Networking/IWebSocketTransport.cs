using System;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;

namespace EchoWorld.Client.Networking
{
    public interface IWebSocketTransport : IDisposable
    {
        WebSocketState State { get; }

        Task ConnectAsync(Uri endpoint, CancellationToken cancellationToken);

        Task SendTextAsync(string message, CancellationToken cancellationToken);

        Task<string> ReceiveTextAsync(CancellationToken cancellationToken);

        Task CloseAsync(CancellationToken cancellationToken);
    }
}

