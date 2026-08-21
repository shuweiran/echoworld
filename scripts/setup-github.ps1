# GitHub 仓库设置脚本
# 用法: .\scripts\setup-github.ps1 -Token "your-github-token"

param(
    [Parameter(Mandatory=$true)]
    [string]$Token
)

$ErrorActionPreference = "Stop"

Write-Host "=== Chat Profile Miner - GitHub 设置 ===" -ForegroundColor Cyan

# 设置 GitHub Token
$env:GITHUB_TOKEN = $Token

# 创建仓库
Write-Host "`n[1/3] 创建 GitHub 仓库..." -ForegroundColor Yellow
$headers = @{
    "Accept" = "application/vnd.github.v3+json"
    "Authorization" = "token $Token"
}

$body = @{
    name = "chat-profile-miner"
    description = "从聊天记录中挖掘对方的喜好、厌恶和礼物信号，输出结构化偏好画像"
    private = $false
    auto_init = $false
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Method Post -Headers $headers -Body $body -ContentType "application/json"
    Write-Host "仓库已创建: $($response.html_url)" -ForegroundColor Green
} catch {
    if ($_.Exception.Message -match "already exists") {
        Write-Host "仓库已存在，继续..." -ForegroundColor Yellow
    } else {
        Write-Host "创建仓库失败: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

# 设置远程仓库
Write-Host "`n[2/3] 设置远程仓库..." -ForegroundColor Yellow
Set-Location "C:\Users\shuweiran\.openclaw\workspace\skills\chat-profile-miner"

# 移除现有远程（如果有）
git remote remove origin 2>$null

# 添加远程仓库
git remote add origin "https://$Token@github.com/shuweiran/chat-profile-miner.git"
Write-Host "远程仓库已设置" -ForegroundColor Green

# 推送代码
Write-Host "`n[3/3] 推送代码..." -ForegroundColor Yellow
git push -u origin master

Write-Host "`n=== 完成 ===" -ForegroundColor Cyan
Write-Host "仓库地址: https://github.com/shuweiran/chat-profile-miner" -ForegroundColor Green
