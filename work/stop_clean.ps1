$conn = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if ($conn) {
  $pid8000 = $conn.OwningProcess
  Stop-Process -Id $pid8000 -Force -ErrorAction SilentlyContinue
  Write-Output "stopped 8000 pid=$pid8000"
} else {
  Write-Output "8000 not listening"
}
Start-Sleep -Seconds 2
$target = 'D:\roleplay-java\target\classes\static'
if (Test-Path -LiteralPath $target) {
  Remove-Item -LiteralPath $target -Recurse -Force
  Write-Output 'target/classes/static removed'
} else {
  Write-Output 'no target/classes/static'
}
