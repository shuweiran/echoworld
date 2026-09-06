using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;

namespace EchoWorld.Client.Editor
{
    /// <summary>Explicit CI/desktop build entry; output stays in Unity's ignored Builds directory.</summary>
    public static class BuildWindowsPlayer
    {
        public static void Build()
        {
            var output = Path.GetFullPath(Path.Combine("Builds", "Windows", "EchoWorldUnity.exe"));
            Directory.CreateDirectory(Path.GetDirectoryName(output) ?? throw new InvalidOperationException("Output directory missing."));
            var group = BuildTargetGroup.Standalone;
            var originalBackend = PlayerSettings.GetScriptingBackend(group);
            // 本机仅安装 Windows Mono variation；临时降级仅影响本次 Build，不改项目默认 IL2CPP 策略。
            PlayerSettings.SetScriptingBackend(group, ScriptingImplementation.Mono2x);
            try
            {
                var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
                {
                    scenes = new[] { "Assets/Scenes/Bootstrap.unity" },
                    locationPathName = output,
                    target = BuildTarget.StandaloneWindows64,
                    options = BuildOptions.None,
                });
                if (report.summary.result != BuildResult.Succeeded)
                {
                    throw new InvalidOperationException($"Unity Windows build failed: {report.summary.result}");
                }
            }
            finally
            {
                PlayerSettings.SetScriptingBackend(group, originalBackend);
            }
        }
    }
}
