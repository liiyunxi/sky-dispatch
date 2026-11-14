# =============================================================================
#  sky-dispatch 本地一键启动脚本（Windows / PowerShell 7+）
#
#  作用：一条命令同时拉起
#    1) Python 算法服务 —— http://localhost:8000（A* 路径规划：绕开禁飞区与高楼障碍）
#    2) Java 后端  —— http://localhost:8080（dev：H2 内存库 + 演示数据）
#    3) 前端控制台 —— http://localhost:5173（自动代理到 8080）
#
#  为什么需要它：
#    只启动前端、或后端误跑在其它端口时，浏览器里【每个接口都会报 500】，
#    且提示含糊，非常难排查。本脚本固定端口、分窗启动、就绪后自动打开浏览器，
#    从根上消除"前后端没对上"的问题。
#
#  用法：
#      pwsh -File .\start-dev.ps1                 # 默认启动【算法服务 + 后端 + 前端】
#      pwsh -File .\start-dev.ps1 -NoAlgorithm    # 只起前后端（后端会自动降级为直线航路）
#      pwsh -File .\start-dev.ps1 -NoBrowser      # 启动后不自动打开浏览器
#
#  停止：直接关闭弹出的三个服务窗口即可。
# =============================================================================
param(
  [switch]$NoAlgorithm,
  [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$backendDir = Join-Path $root 'backend'
$frontendDir = Join-Path $root 'frontend'
$algorithmDir = Join-Path $root 'algorithm-service'

$backendPort = 8080
$frontendPort = 5173
$algorithmPort = 8000

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "    $msg" -ForegroundColor Yellow }

# --- 1. 基本检查 -------------------------------------------------------------
if (-not (Test-Path (Join-Path $backendDir 'pom.xml')) -or
    -not (Test-Path (Join-Path $frontendDir 'package.json'))) {
  throw "未找到 backend/ 或 frontend/，请在项目根目录（含 start-dev.ps1 的目录）下运行本脚本。"
}

function Test-Command($name) {
  return $null -ne (Get-Command $name -ErrorAction SilentlyContinue)
}

if (-not (Test-Command 'mvn')) {
  throw "未检测到 mvn，请先安装 Maven 3.8+ 并加入 PATH。"
}
if (-not (Test-Command 'npm')) {
  throw "未检测到 npm，请先安装 Node.js 18+ 并加入 PATH。"
}

function Test-PortListening($port) {
  return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

# --- 2. 前端依赖（缺失时自动安装，避免 vite 起不来） ---------------------------
if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
  Write-Step "首次运行，安装前端依赖（npm install，可能需要几分钟）..."
  Push-Location $frontendDir
  try { npm install } finally { Pop-Location }
}

# --- 3. 在独立窗口启动各服务（端口已被占用则复用，不重复拉起） ------------------
function Start-ServiceWindow($title, $workDir, $command) {
  Write-Step "新窗口启动：$title"
  Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoExit',
    '-Command',
    "`$Host.UI.RawUI.WindowTitle='$title'; Set-Location '$workDir'; $command"
  ) | Out-Null
}

if (Test-PortListening $backendPort) {
  Write-Warn2 "检测到 $backendPort 端口已有服务，视为后端已启动，跳过（如不是本系统后端请先释放该端口）。"
} else {
  # dev profile：H2 内存库 + 自动灌演示数据；算法服务默认开启（见上方 8000 窗口）
  Start-ServiceWindow 'sky-dispatch BACKEND :8080' $backendDir 'mvn spring-boot:run'
}

# 算法服务默认启动（dev 后端默认开启了 A* 路径规划；关掉它派单会 503）
if (-not $NoAlgorithm) {
  if (Test-PortListening $algorithmPort) {
    Write-Warn2 "检测到 $algorithmPort 端口已有服务，视为算法服务已启动，跳过。"
  } elseif (-not (Test-Path $algorithmDir)) {
    Write-Warn2 "未找到 algorithm-service/，跳过程序化 A*；可改用 -NoAlgorithm 显式关闭算法开关。"
  } else {
    # uv 的全局缓存目录在部分受限环境下不可写，统一指到项目内，避免启动即失败
    $uvCache = Join-Path $root '.build\uv-cache'
    $algoCmd = if (Test-Command 'uv') {
      "`$env:UV_CACHE_DIR='$uvCache'; uv run --no-sync uvicorn app.main:app --port 8000"
    } else {
      Write-Warn2 "未检测到 uv，尝试 python -m uvicorn；如失败请先 uv sync。"
      'python -m uvicorn app.main:app --port 8000'
    }
    Start-ServiceWindow 'sky-dispatch ALGORITHM :8000' $algorithmDir $algoCmd
  }
} else {
  Write-Warn2 "-NoAlgorithm：不启动 Python 算法服务。请给后端加 -Dsky.algorithm.enabled=false，否则派单会因算法不可用而 503。"
}

if (Test-PortListening $frontendPort) {
  Write-Warn2 "检测到 $frontendPort 端口已有服务，视为前端已启动，跳过。"
} else {
  Start-ServiceWindow 'sky-dispatch FRONTEND :5173' $frontendDir 'npm run dev'
}

# --- 4. 等待后端就绪（首次编译较慢，最多等 150 秒） ---------------------------
Write-Step '等待后端 8080 就绪 ...'
$deadline = (Get-Date).AddSeconds(150)
$ready = $false
while ((Get-Date) -lt $deadline) {
  if (Test-PortListening $backendPort) {
    try {
      $health = Invoke-RestMethod -Uri "http://127.0.0.1:$backendPort/actuator/health" -TimeoutSec 5
      if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {
      # 端口已开但应用还在初始化，继续等
    }
  }
  Start-Sleep -Seconds 3
  Write-Host '.' -NoNewline
}
Write-Host ''

if ($ready) {
  Write-Ok '后端已就绪（H2 演示数据已自动加载）。'
} else {
  Write-Warn2 '后端在限定时间内未就绪，请查看【BACKEND :8080】窗口的日志（常见原因：依赖首次下载较慢，可稍后重试）。'
}

# --- 5. 打开浏览器 ------------------------------------------------------------
$url = "http://localhost:$frontendPort"
if (-not $NoBrowser) {
  Write-Step "打开控制台：$url"
  Start-Process $url
} else {
  Write-Ok "前端地址：$url"
}

Write-Host ''
Write-Host '启动器完成。各服务运行在独立窗口，关闭对应窗口即可停止。' -ForegroundColor Green
Write-Host '按回车键关闭本启动器窗口（不影响已启动的服务）...' -ForegroundColor DarkGray
try { [void](Read-Host) } catch { }
