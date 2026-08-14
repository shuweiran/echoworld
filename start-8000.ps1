param([string]$Jar = "D:\roleplay-java\target\roleplay-engine-1.0.0-SNAPSHOT.jar")
$ErrorActionPreference = "Stop"
$key = node -e "console.log(require(process.env.USERPROFILE+'/.openclaw/openclaw.json').models.providers.deepseek.apiKey)"
if (-not $key) { throw "API key not found" }
$env:ROLEPLAY_LLM_API_KEY = $key
$out = "D:\roleplay-java\target\boot-8000.log"
$err = "D:\roleplay-java\target\boot-8000-err.log"
Start-Process -FilePath "java" -ArgumentList "-jar",$Jar -WorkingDirectory "D:\roleplay-java" -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
Write-Output "STARTED_WITH_KEY_LEN=$($key.Length) jar=$Jar"
