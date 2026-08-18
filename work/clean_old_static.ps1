$old = @(
  'D:\roleplay-java\src\main\resources\static\assets\index-DDK3_Yan.js',
  'D:\roleplay-java\src\main\resources\static\assets\index-CsSegv7D.js',
  'D:\roleplay-java\src\main\resources\static\assets\index-B15NZqdV.css'
)
foreach ($p in $old) {
  if (Test-Path -LiteralPath $p) {
    Remove-Item -LiteralPath $p -Force
    Write-Output "removed $p"
  }
}
Get-ChildItem 'D:\roleplay-java\src\main\resources\static\assets' -File | Select-Object Name, Length
Get-Content 'D:\roleplay-java\src\main\resources\static\index.html' | Select-String -Pattern 'assets/'
