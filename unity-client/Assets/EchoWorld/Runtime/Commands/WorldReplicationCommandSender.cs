using System;
using System.Collections.Generic;
using System.Net.WebSockets;
using System.Threading.Tasks;
using EchoWorld.Client.Networking;
using EchoWorld.Client.Protocol;
using UnityEngine;

namespace EchoWorld.Client.Commands
{
    /// <summary>
    /// Exact client-to-server command surface currently accepted by /ws/world.
    /// Gameplay movement/interaction must not be sent on this replication socket.
    /// </summary>
    public sealed class WorldReplicationCommandSender : MonoBehaviour
    {
        private EchoWorldClient _client;
        private string _clientId;
        private InterestDto _initialInterest;

        public void Initialize(EchoWorldClient client, string clientId, InterestDto initialInterest)
        {
            _client = client ?? throw new ArgumentNullException(nameof(client));
            if (string.IsNullOrWhiteSpace(clientId))
            {
                throw new ArgumentException("clientId is required.", nameof(clientId));
            }

            _clientId = clientId;
            _initialInterest = initialInterest ?? new InterestDto();
            _client.ConnectionStateChanged += OnConnectionStateChanged;
        }

        public Task SendHelloAsync()
        {
            return _client.SendAsync(ProtocolConstants.Hello, new HelloDto
            {
                ClientId = _clientId,
                ProtocolVersion = ProtocolConstants.CurrentVersion,
                FocusCell = CloneCell(_initialInterest.FocusCell),
                RadiusCells = Math.Max(0, _initialInterest.RadiusCells),
                NarrativeSubscriptions = new List<string>(
                    _initialInterest.NarrativeSubscriptions ?? new List<string>())
            });
        }

        public Task UpdateInterestAsync(InterestDto interest)
        {
            if (interest == null)
            {
                throw new ArgumentNullException(nameof(interest));
            }

            return _client.SendAsync(ProtocolConstants.Interest, new InterestDto
            {
                FocusCell = CloneCell(interest.FocusCell),
                RadiusCells = Math.Max(0, interest.RadiusCells),
                NarrativeSubscriptions = new List<string>(
                    interest.NarrativeSubscriptions ?? new List<string>())
            });
        }

        public Task AcknowledgeAsync(long sequence)
        {
            return _client.SendAsync(ProtocolConstants.Ack, new SequenceDto { Sequence = sequence });
        }

        public Task ReplayAfterAsync(long sequence)
        {
            return _client.SendAsync(ProtocolConstants.Replay, new SequenceDto { Sequence = sequence });
        }

        private async void OnConnectionStateChanged(WebSocketState state)
        {
            if (state != WebSocketState.Open)
            {
                return;
            }

            try
            {
                await SendHelloAsync();
            }
            catch (Exception exception)
            {
                Debug.LogWarning($"EchoWorld hello failed: {exception.Message}");
            }
        }

        private static SpatialCellDto CloneCell(SpatialCellDto source)
        {
            return source == null ? null : new SpatialCellDto
            {
                ZoneId = source.ZoneId,
                FloorId = source.FloorId,
                X = source.X,
                Z = source.Z
            };
        }

        private void OnDestroy()
        {
            if (_client != null)
            {
                _client.ConnectionStateChanged -= OnConnectionStateChanged;
            }
        }
    }
}
