# =============================================================================
#  sky-dispatch 端到端冒烟测试
#
#  用法（PowerShell，先启动 Java 后端与 Python 算法服务）：
#      pwsh -File tools\smoke-test.ps1
#      pwsh -File tools\smoke-test.ps1 -BaseUrl http://localhost:8080
#
#  它按真实业务流程走一遍：健康检查 → 空域 → 下单 → 派单 → 看决策 →
#  手动步进仿真 → 查任务/遥测/冲突 → 导出 CSV。
#  任何一步失败都会打红字并以退出码 1 结束，可直接接进 CI。
# =============================================================================
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$AlgoUrl = 'http://127.0.0.1:8000'
)

$ErrorActionPreference = 'Stop'
$api = "$BaseUrl/api/v1"
$script:failed = 0

function Step($name) { Write-Host "`n=== $name ===" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Bad($msg)   { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:failed++ }

function Get-Json($url) {
    try { return Invoke-RestMethod -Uri $url -TimeoutSec 20 }
    catch { Bad "GET $url -> $($_.Exception.Message)"; return $null }
}

function Post-Json($url, $body) {
    try {
        if ($null -eq $body) {
            return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -TimeoutSec 30
        }
        return Invoke-RestMethod -Uri $url -Method Post -Body ($body | ConvertTo-Json -Depth 6) `
            -ContentType 'application/json' -TimeoutSec 30
    } catch {
        Bad "POST $url -> $($_.Exception.Message)"
        return $null
    }
}

# ---------------------------------------------------------------------------
Step '1. 服务健康检查'
# ---------------------------------------------------------------------------
$health = Get-Json "$BaseUrl/actuator/health"
if ($health.status -eq 'UP') { Ok "Java 后端 UP（数据库 $($health.components.db.details.database)）" }
else { Bad 'Java 后端未就绪' }

$algo = Get-Json "$AlgoUrl/health"
if ($algo.status -eq 'ok') { Ok "算法服务 ok（Python $($algo.python)）" }
else { Bad '算法服务未就绪，派单会返回 503' }

$algoViaJava = Get-Json "$api/dashboard/algorithm-health"
if ($algoViaJava.data.available) { Ok "Java 侧探活算法服务成功：$($algoViaJava.data.baseUrl)" }
else { Bad 'Java 无法访问算法服务' }

# ---------------------------------------------------------------------------
Step '2. 基础数据（空域 / 无人机）'
# ---------------------------------------------------------------------------
$zones = Get-Json "$api/airspace/zones"
if ($zones.data.Count -gt 0) { Ok "空域 $($zones.data.Count) 个，例如 $($zones.data[0].code) $($zones.data[0].name)" }
else { Bad '空域为空' }

$drones = Get-Json "$api/drones"
if ($drones.data.Count -gt 0) { Ok "无人机 $($drones.data.Count) 架，例如 $($drones.data[0].code) 电量 $($drones.data[0].batteryPct)%" }
else { Bad '无人机为空' }

# 注册一架专供本次冒烟测试的无人机，停在取货点上。
# 为什么要这样：仿真引擎会自动把空闲机派出去，如果借用现有机队，
# "无可用无人机"会让冒烟测试出现与被测代码无关的红色。
$testDrone = Post-Json "$api/drones" @{
    code      = 'UAV-TEST'
    modelCode = 'SKY-X4'
    batteryPct = 100
    home      = @{ lat = 31.2304; lon = 121.4737; address = '冒烟测试点' }
}
if ($testDrone.code -eq 0) { Ok "已注册测试无人机 UAV-TEST（停在取货点，保证派单必成功）" }
elseif ($testDrone.message -like '*已存在*') { Ok '测试无人机 UAV-TEST 已存在，直接复用' }
else { Write-Host "  [WARN] 注册测试无人机失败：$($testDrone.message)" -ForegroundColor Yellow }

# ---------------------------------------------------------------------------
Step '3. 创建订单'
# ---------------------------------------------------------------------------
$orderBody = @{
    customerName  = '冒烟测试医院'
    customerPhone = '021-88888888'
    cargoName     = '测试试剂'
    weightKg      = 2.5
    priority      = 'URGENT'
    pickup        = @{ lat = 31.2304; lon = 121.4737; address = '人民广场站' }
    dropoff       = @{ lat = 31.2480; lon = 121.4520; address = '测试目的地' }
    remark        = 'smoke-test'
}
$created = Post-Json "$api/orders" $orderBody
if ($created.code -eq 0) {
    $orderId = $created.data.id
    Ok "订单已创建 id=$orderId orderNo=$($created.data.orderNo) status=$($created.data.status)"
} else { Bad "创建订单失败：$($created.message)"; exit 1 }

# ---------------------------------------------------------------------------
Step '4. 三种策略各评估一次（只打分明细，不做副作用对比）'
# ---------------------------------------------------------------------------
foreach ($strategy in @('NEAREST_AVAILABLE', 'BATTERY_FIRST', 'LOAD_BALANCE')) {
    $res = Post-Json "$api/dispatch/orders/$orderId`?strategy=$strategy" $null
    if ($res.code -eq 0) {
        Ok "策略 $strategy -> 任务 $($res.data.task.taskNo)，派给 $($res.data.task.droneCode)：$($res.data.message)"
        break   # 第一次派单成功后订单已 ASSIGNED，后续策略需要新订单，这里只演示一种
    } elseif ($res.code -eq 1004) {
        Bad "策略 $strategy 无可用无人机：$($res.message)"
    } else {
        Write-Host "  [SKIP] 策略 $strategy 未执行：$($res.message)" -ForegroundColor Yellow
        break
    }
}

$decisions = Get-Json "$api/dispatch/decisions?orderId=$orderId"
if ($decisions.data.Count -gt 0) {
    $picked = $decisions.data | Where-Object { $_.selected } | Select-Object -First 1
    $rejected = $decisions.data | Where-Object { -not $_.selected }
    Ok "决策明细 $($decisions.data.Count) 条：选中 $($picked.droneCode)（得分 $($picked.score)）"
    foreach ($r in $rejected | Select-Object -First 3) {
        Write-Host "         淘汰 $($r.droneCode)：$($r.rejectedReason)" -ForegroundColor DarkGray
    }
} else { Bad '没有决策明细，可解释性缺失' }

# ---------------------------------------------------------------------------
Step '5. 任务与航路'
# ---------------------------------------------------------------------------
$tasks = Get-Json "$api/tasks?size=5"
if ($tasks.data.content.Count -gt 0) {
    $task = $tasks.data.content[0]
    Ok "任务 $($task.taskNo) 状态 $($task.status)，规划航程 $($task.plannedDistanceM)m，预计能耗 $($task.plannedEnergyWh)Wh"

    $wps = Get-Json "$api/tasks/$($task.id)/waypoints"
    if ($wps.data.Count -ge 2) {
        Ok "航路点 $($wps.data.Count) 个；首点 $($wps.data[0].lat),$($wps.data[0].lon) → 末点 $($wps.data[-1].lat),$($wps.data[-1].lon)，累计 $($wps.data[-1].cumulativeDistanceM)m"
    } else { Bad '航路点少于 2 个' }
} else { Bad '没有任务' }

# ---------------------------------------------------------------------------
Step '6. 手动步进仿真 20 拍，观察位置与电量变化'
# ---------------------------------------------------------------------------
$firstFrame = $null
$lastFrame = $null
for ($i = 1; $i -le 20; $i++) {
    $tick = Post-Json "$api/simulation/tick" $null
    if ($null -eq $tick -or $tick.code -ne 0) { break }
    if ($i -eq 1) { $firstBattery = $tick.data }
    $lastTick = $tick
}
$frames = Get-Json "$api/dashboard/drones"
$moving = $frames.data | Where-Object { $_.status -ne 'IDLE' -and $_.status -ne 'OFFLINE' }
if ($moving.Count -gt 0) {
    $d = $moving[0]
    Ok "推进到第 $($lastTick.data.stepIndex) 拍 / 仿真 $([math]::Round($lastTick.data.simTimeS,1))s"
    Ok "$($d.droneCode) 状态=$($d.status) 位置=$([math]::Round($d.lat,5)),$([math]::Round($d.lon,5)) 电量=$($d.batteryPct)% 进度=$($d.progressPct)%"
} else { Bad '步进后仍无无人机在执行任务' }

$run = Get-Json "$api/simulation/runs/current"
if ($run.data.tickCount -gt 0) { Ok "仿真运行 #$($run.data.id) 状态 $($run.data.status)，已推进 $($run.data.tickCount) 拍" }
else { Bad '仿真运行状态异常' }

# ---------------------------------------------------------------------------
Step '7. 冲突与事件'
# ---------------------------------------------------------------------------
$conflicts = Get-Json "$api/dashboard/conflicts"
Ok "冲突记录 $($conflicts.data.Count) 条（多机同高度巡航时会出现 DRONE_DRONE 告警）"
foreach ($c in $conflicts.data | Select-Object -First 3) {
    Write-Host "         [$($c.severity)] $($c.conflictType): $($c.message)" -ForegroundColor DarkGray
}

$events = Get-Json "$api/tasks/$($task.id)/events"
if ($events.data.Count -gt 0) {
    Ok "任务事件时间线 $($events.data.Count) 条，例如 $($events.data[0].eventType)：$($events.data[0].message)"
} else { Bad '任务没有事件记录' }

# ---------------------------------------------------------------------------
Step '8. KPI 与 CSV 导出'
# ---------------------------------------------------------------------------
$kpi = Get-Json "$api/dashboard/kpi"
Ok "KPI：订单 $($kpi.data.totalOrders) / 已送达 $($kpi.data.deliveredOrders) / 活动无人机 $($kpi.data.activeDrones) / 准时率 $($kpi.data.onTimeRate) / 未处理冲突 $($kpi.data.openConflicts)"

$csvUrl = "$api/reports/tasks/$($task.id)/telemetry.csv"
try {
    $resp = Invoke-WebRequest -Uri $csvUrl -TimeoutSec 20
    $lines = ($resp.Content -split "`n").Count
    if ($lines -gt 1) { Ok "遥测 CSV 导出成功，$($lines - 1) 行数据"; Write-Host "         前两行：`n$(($resp.Content -split "`n")[0..1] -join "`n")" -ForegroundColor DarkGray }
    else { Bad '遥测 CSV 为空（可能还没到落库间隔）' }
} catch { Bad "CSV 导出失败：$($_.Exception.Message)" }

$tasksCsv = Invoke-WebRequest -Uri "$api/reports/tasks.csv" -TimeoutSec 20
Ok "任务清单 CSV 导出成功（$((($tasksCsv.Content -split "`n").Count) - 1) 行）"

# ---------------------------------------------------------------------------
Write-Host ''
if ($script:failed -eq 0) {
    Write-Host '全部冒烟检查通过 ✅' -ForegroundColor Green
    exit 0
} else {
    Write-Host "冒烟检查有 $script:failed 项失败 ❌" -ForegroundColor Red
    exit 1
}
