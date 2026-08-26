if (-not $env:ROLEPLAY_LLM_API_KEY) {
    throw "Set ROLEPLAY_LLM_API_KEY in the process environment before starting EchoWorld."
}
if (-not $env:ROLEPLAY_MIMO_TTS_KEY) {
    throw "Set ROLEPLAY_MIMO_TTS_KEY in the process environment before starting EchoWorld."
}
Start-Process -FilePath "java" -ArgumentList "-jar","D:\echoworld\target\roleplay-engine-0.1.0.jar" -WorkingDirectory "D:\echoworld" -RedirectStandardOutput "D:\echoworld\logs\p0825g_stdout.log" -RedirectStandardError "D:\echoworld\logs\p0825g_stderr.log" -NoNewWindow
