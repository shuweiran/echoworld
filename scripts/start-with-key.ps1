# Read key using node (PowerShell ConvertFrom-Json chokes on large JSON)
$key = & node -e "const c=require('fs').readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json','utf8');const j=JSON.parse(c);console.log(j.models.providers.deepseek.apiKey)"
$env:ROLEPLAY_LLM_API_KEY = $key
Write-Host "KEY_LEN=$($key.Length)"
Start-Process java -ArgumentList "-jar","D:\roleplay-java\target\roleplay-engine-1.0.0-SNAPSHOT.jar" -WorkingDirectory "D:\roleplay-java" -WindowStyle Hidden
Write-Host "STARTED"
