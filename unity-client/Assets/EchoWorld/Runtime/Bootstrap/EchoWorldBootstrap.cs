using System;
using EchoWorld.Client.Assets;
using EchoWorld.Client.Commands;
using EchoWorld.Client.Networking;
using EchoWorld.Client.Presentation;
using EchoWorld.Client.Protocol;
using EchoWorld.Client.Replica;
using UnityEngine;

namespace EchoWorld.Client.Bootstrap
{
    public static class EchoWorldBootstrap
    {
        private const string DefaultEndpoint = "ws://127.0.0.1:8000/ws/world";
        private static bool _initialized;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        private static void Initialize()
        {
            if (_initialized)
            {
                return;
            }

            _initialized = true;
            var root = new GameObject("EchoWorld.Client");
            UnityEngine.Object.DontDestroyOnLoad(root);

            var endpoint = Environment.GetEnvironmentVariable("ECHOWORLD_WS_URL") ?? DefaultEndpoint;
            var autoConnect = string.Equals(
                Environment.GetEnvironmentVariable("ECHOWORLD_WS_AUTO_CONNECT"),
                "1",
                StringComparison.Ordinal);
            var clientId = Environment.GetEnvironmentVariable("ECHOWORLD_CLIENT_ID");
            if (string.IsNullOrWhiteSpace(clientId))
            {
                clientId = $"unity-{Guid.NewGuid():N}";
            }

            var client = root.AddComponent<EchoWorldClient>();
            client.Configure(endpoint, autoConnect);
            client.ProtocolWarning += message => Debug.LogWarning($"EchoWorld protocol: {message}");

            var commandSender = root.AddComponent<WorldReplicationCommandSender>();
            commandSender.Initialize(client, clientId, new InterestDto
            {
                FocusCell = new SpatialCellDto
                {
                    ZoneId = "world",
                    FloorId = "ground",
                    X = 0,
                    Z = 0
                },
                RadiusCells = 2
            });

            var replicaHost = root.AddComponent<WorldReplicaHost>();
            replicaHost.Initialize(client, commandSender);

            var resolver = root.AddComponent<AddressablesAssetResolver>();
            var presentation = root.AddComponent<WorldPresentationController>();
            presentation.Initialize(replicaHost, resolver);

            EnsureDefaultCameraAndLight();
        }

        private static void EnsureDefaultCameraAndLight()
        {
            if (Camera.main == null)
            {
                var cameraObject = new GameObject("Main Camera");
                cameraObject.tag = "MainCamera";
                var camera = cameraObject.AddComponent<Camera>();
                camera.clearFlags = CameraClearFlags.SolidColor;
                camera.backgroundColor = new Color(0.02f, 0.025f, 0.04f);
                cameraObject.transform.SetPositionAndRotation(
                    new Vector3(0f, 8f, -12f),
                    Quaternion.Euler(24f, 0f, 0f));
            }

            if (UnityEngine.Object.FindFirstObjectByType<Light>() == null)
            {
                var lightObject = new GameObject("Directional Light");
                var light = lightObject.AddComponent<Light>();
                light.type = LightType.Directional;
                light.intensity = 1.1f;
                lightObject.transform.rotation = Quaternion.Euler(45f, -35f, 0f);
            }
        }
    }
}
