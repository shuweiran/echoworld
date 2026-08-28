# ox alpha（GLM-5.3 Flash）启动脚本：key 从 openclaw.json 本地读取，不落源码
# 复用原 8000 进程参数：-Djdk.net.unixdomain.tmpdir（P-0827-D loopback 修复）+ server.port/address
$ErrorActionPreference = "Stop"
# Read Zhipu key for glm-5.3-flash (formerly OpenRouter stealth/ox-alpha; testing period ended 2026-08,
# official 404 notice: "This model was ZAI's GLM-5.3 Flash". tokenra relay dead: 403 quota=$0.)
$key = & node -e "const c=require('fs').readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json','utf8');const j=JSON.parse(c);console.log(j.models.providers.zhipu.apiKey)"
$env:ROLEPLAY_LLM_API_KEY = ***
$env:ROLEPLAY_ARBITER_LLM_API_KEY = ***
Write-Host "KEY_LEN=$($key.Length)"
$java = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe"
$out = "D:\echoworld\target\server-0828d.out.log"
$err = "D:\echoworld\target\server-0828d.err.log"
Start-Process $java -ArgumentList "-Djdk.net.unixdomain.tmpdir=C:\tmp","-jar","D:\echoworld\target\roleplay-engine-0.1.1.jar","--server.port=8000","--server.address=127.0.0.1" -WorkingDirectory "D:\echoworld" -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
Write-Host "STARTED log=$out"
