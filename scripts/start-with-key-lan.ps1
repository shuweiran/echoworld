# ox alpha (GLM-5.3 Flash) LAN start script: read zhipu key from openclaw.json at runtime.
# Derived from start-with-key.ps1 (P-0828-D jdk params) but binds 0.0.0.0 so the phone can
# reach the backend over Tailscale (100.125.135.80). No secret literals in this file.
$ErrorActionPreference = "Stop"
# Read Zhipu key for glm-5.3-flash from local openclaw.json (never inline the key).
$key = & node -e "const c=require('fs').readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json','utf8');const j=JSON.parse(c);console.log(j.models.providers.zhipu.apiKey)"
$env:ROLEPLAY_LLM_API_KEY = $key
$env:ROLEPLAY_ARBITER_LLM_API_KEY = $key
Write-Host "KEY_LEN=$($key.Length)"
$java = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe"
$out = "D:\echoworld\target\server-lan.out.log"
$err = "D:\echoworld\target\server-lan.err.log"
Start-Process $java -ArgumentList "-Djdk.net.unixdomain.tmpdir=C:\tmp","-jar","D:\echoworld\target\roleplay-engine-0.1.1.jar","--server.port=8000","--server.address=0.0.0.0" -WorkingDirectory "D:\echoworld" -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
Write-Host "STARTED log=$out"
