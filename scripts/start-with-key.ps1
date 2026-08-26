# Read Tokenra key for Ox Alpha model
$key = & node -e "const c=require('fs').readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json','utf8');const j=JSON.parse(c);console.log(j.models.providers.tokenra.apiKey)"
$env:ROLEPLAY_LLM_API_KEY = $key
$env:ROLEPLAY_ARBITER_LLM_API_KEY = $key
Write-Host "KEY_LEN=$($key.Length)"
Start-Process java -ArgumentList "-jar","D:\echoworld\target\roleplay-engine-0.1.0.jar" -WorkingDirectory "D:\echoworld" -WindowStyle Hidden
Write-Host "STARTED"
