using System;
using UnityEngine;

namespace EchoWorld.Client.Presentation
{
    /// <summary>Unity-only deterministic 3D environment. It never writes gameplay state back to the server.</summary>
    public sealed class WorldEnvironmentGenerator : MonoBehaviour
    {
        private string _worldId = "world";
        private string _seedText = "world";

        public void Configure(string worldId, string seedText)
        {
            _worldId = string.IsNullOrWhiteSpace(worldId) ? "world" : worldId;
            _seedText = string.IsNullOrWhiteSpace(seedText) ? _worldId : seedText;
        }

        private void Start()
        {
            var random = new System.Random(_seedText.GetHashCode());
            var root = new GameObject($"GeneratedWorld:{_worldId}");
            var ground = GameObject.CreatePrimitive(PrimitiveType.Plane);
            ground.name = "Ground";
            ground.transform.SetParent(root.transform, false);
            ground.transform.localScale = new Vector3(6f, 1f, 6f);
            ApplyColor(ground, new Color(0.12f, 0.24f, 0.17f));

            // Fixed 48-object budget keeps the fallback world comfortably above the 30 FPS floor on modest GPUs.
            for (var i = 0; i < 48; i++)
            {
                var item = GameObject.CreatePrimitive(i % 3 == 0 ? PrimitiveType.Cylinder : PrimitiveType.Cube);
                item.name = $"WorldProp:{i}";
                item.transform.SetParent(root.transform, false);
                item.transform.localPosition = new Vector3(random.Next(-26, 27), i % 3 == 0 ? 1.1f : 0.75f, random.Next(-26, 27));
                item.transform.localScale = i % 3 == 0 ? new Vector3(1.4f, 2.2f, 1.4f) : new Vector3(1.5f, 1.5f, 1.5f);
                ApplyColor(item, i % 3 == 0 ? new Color(0.08f, 0.32f, 0.13f) : new Color(0.32f, 0.25f, 0.18f));
            }
        }

        private static void ApplyColor(GameObject target, Color color)
        {
            var shader = Shader.Find("EchoWorld/WorldUnlit");
            if (shader == null) throw new InvalidOperationException("EchoWorld/WorldUnlit shader is missing.");
            var material = new Material(shader) { color = color };
            target.GetComponent<Renderer>().sharedMaterial = material;
        }
    }
}
