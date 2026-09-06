using UnityEngine;

namespace EchoWorld.Client.Presentation
{
    /// <summary>Sets a 60 FPS target and makes sustained sub-30 FPS visible during development builds.</summary>
    public sealed class FrameRateGuard : MonoBehaviour
    {
        private float _elapsed;
        private int _frames;

        private void Awake()
        {
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = 60;
        }

        private void Update()
        {
            // Test Runner uses NullGfxDevice; its frame cadence cannot代表真实 Unity Player 性能。
            if (Application.isBatchMode) return;
            _elapsed += Time.unscaledDeltaTime;
            _frames++;
            if (_elapsed < 1f) return;
            var fps = _frames / _elapsed;
            if (fps < 30f)
            {
                Debug.LogWarning($"EchoWorld 3D performance below 30 FPS: {fps:F1}");
            }
            _elapsed = 0f;
            _frames = 0;
        }
    }
}
