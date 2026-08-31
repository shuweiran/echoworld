using System;
using EchoWorld.Client.Commands;
using EchoWorld.Client.Networking;
using EchoWorld.Client.Protocol;
using UnityEngine;

namespace EchoWorld.Client.Replica
{
    public sealed class WorldReplicaHost : MonoBehaviour
    {
        private EchoWorldClient _client;
        private WorldReplicationCommandSender _commands;

        public WorldReplica Replica { get; } = new WorldReplica();

        public event Action<ReplicaApplyResult> ReplicaChanged;

        public void Initialize(EchoWorldClient client, WorldReplicationCommandSender commands)
        {
            _client = client ?? throw new ArgumentNullException(nameof(client));
            _commands = commands ?? throw new ArgumentNullException(nameof(commands));
            _client.ReplicationFrameReceived += OnReplicationFrame;
        }

        private async void OnReplicationFrame(ReplicationFrameDto frame)
        {
            var result = Replica.Apply(frame);
            if (result.Status == ReplicaApplyStatus.Applied)
            {
                ReplicaChanged?.Invoke(result);
                try
                {
                    await _commands.AcknowledgeAsync(frame.Sequence);
                }
                catch (Exception exception)
                {
                    Debug.LogWarning($"EchoWorld ACK failed: {exception.Message}");
                }
            }
            else if (result.RequiresReplay && Replica.LastSequence >= 0)
            {
                try
                {
                    await _commands.ReplayAfterAsync(Replica.LastSequence);
                }
                catch (Exception exception)
                {
                    Debug.LogWarning($"EchoWorld replay request failed: {exception.Message}");
                }
            }
        }

        private void OnDestroy()
        {
            if (_client != null)
            {
                _client.ReplicationFrameReceived -= OnReplicationFrame;
            }
        }
    }
}
