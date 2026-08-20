# 错峰无人值守：aiagent 全量回归快验证（offpeak-test-defer）
# 由 Windows 任务计划每日 13:00/19:00 触发（DeepSeek 空闲半价窗口）。
# 默认快验证模式（mvn test + 重建 jar + 重启后端 + npm build + pwtest，1 小时内完成，无人值守安全）；
# 加 -IncludeDeep 才跑 145 题深度测试（长耗时，建议在打开 Codex 的会话内由「非高峰自动启动」执行，人在场可干预）。
param([switch]$SkipShutdown, [switch]$IncludeDeep)
$ErrorActionPreference = 'Continue'
$root = 'D:\codestudy\大学项目\aiagent数据分析平台'
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $root 'tools\_test_stat'
$logFile = Join-Path $logDir "_offpeak_run_$stamp.log"
$summaryFile = Join-Path $logDir '错峰汇总-2026-08-20.md'
function Log($m) { $line = "[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Output $line; Add-Content -LiteralPath $logFile -Value $line -Encoding utf8 }
Log "=== 错峰测试开始 $stamp ==="
$java = 'C:\Users\13975\.jdks\openjdk-22.0.1\bin\java.exe'
$mvn = 'C:\Program Files\JetBrains\IntelliJ IDEA 2023.2\plugins\maven\lib\maven3\bin\mvn.cmd'
$env:JAVA_HOME = 'C:\Users\13975\.jdks\openjdk-22.0.1'
$summary = [System.Collections.Generic.List[string]]::new()
$deepOk = $false

function Stop-Backend {
  $conns = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
  foreach ($c in $conns) {
    try { Stop-Process -Id $c.OwningProcess -Force -ErrorAction Stop; Log "stopped pid $($c.OwningProcess) on 8080" } catch { Log "stop 8080 pid $($c.OwningProcess) failed: $_" }
  }
  Start-Sleep -Seconds 2
}
function Wait-Port($port, $timeoutSec) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { return $true }
    Start-Sleep -Seconds 3
  }
  return $false
}

Log "STEP 1: 全量 mvn test"
& $mvn -f backend\pom.xml test 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_mvn_test_$stamp.log") -Encoding utf8
$mvnExit = $LASTEXITCODE
$run = 0; $fail = 0; $err = 0; $skip = 0
Get-ChildItem backend\target\surefire-reports -Filter '*.txt' -ErrorAction SilentlyContinue | ForEach-Object {
  Get-Content $_.FullName | Select-String 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)' | ForEach-Object {
    if ($_.Matches[0].Groups[1].Value) { $run += [int]$_.Matches[0].Groups[1].Value; $fail += [int]$_.Matches[0].Groups[2].Value; $err += [int]$_.Matches[0].Groups[3].Value; $skip += [int]$_.Matches[0].Groups[4].Value }
  }
}
Log "mvn test: run=$run fail=$fail err=$err skip=$skip exit=$mvnExit"
$summary.Add("## 全量 mvn test: run=$run fail=$fail err=$err skip=$skip (exit=$mvnExit)")

Log "STEP 2: 重建 jar"
Stop-Backend
& $mvn -f backend\pom.xml package -DskipTests 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_mvn_pkg_$stamp.log") -Encoding utf8
$pkgExit = $LASTEXITCODE
$jar = Join-Path $root 'backend\target\ai-agent-data-platform-1.0.0-SNAPSHOT.jar'
Log "mvn package exit=$pkgExit jar=$(Test-Path $jar)"
$summary.Add("## 构建 jar: exit=$pkgExit 存在=$(Test-Path $jar)")

Log "STEP 3: 启动后端(DB 读 key, 无需环境变量)"
Stop-Backend
if (Test-Path $jar) {
  $outLog = Join-Path $logDir "_backend_$stamp.log"
  $errLog = Join-Path $logDir "_backend_err_$stamp.log"
  $p = Start-Process -WindowStyle Hidden -FilePath $java -ArgumentList @('-jar', $jar) -WorkingDirectory (Join-Path $root 'backend') -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
  Log "backend pid=$($p.Id) 等待 8080..."
  $up = Wait-Port 8080 120
  Log "backend up=$up"
  Start-Sleep -Seconds 3
  try {
    $login = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
    Log "backend login ok code=$($login.code)"
  } catch { Log "backend login fail: $_" }
  Log "STEP 3.5: 分页契约验证(长列表分页)"
  $pgExit = 1
  try {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'tools\_test_pagination\verify_pagination.ps1') 2>&1 | ForEach-Object { Log $_ }
    $pgExit = $LASTEXITCODE
  } catch { Log "分页验证异常: $_" }
  $summary.Add("## 分页契约验证(长列表分页): exit=$pgExit (0=全过)")
} else { Log "jar 不存在，跳过启动" }

$deepOk = $false
if ($IncludeDeep) {
  Log "STEP 4: 深度测试第4轮 (145题 全量LLM)"
  Set-Location (Join-Path $root 'tools\_test_stat')
  if (Test-Path 'results') {
    if (Test-Path 'results_round3') { Remove-Item 'results_round3' -Recurse -Force }
    Move-Item 'results' 'results_round3'
    Log "round3 已归档为 results_round3"
  }
  node deep_test.mjs --concurrency=3 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_round4_run_$stamp.log") -Encoding utf8
  $deepExit = $LASTEXITCODE
  $nRes = (Get-ChildItem 'results' -Filter '*.json' -ErrorAction SilentlyContinue).Count
  Log "deep_test exit=$deepExit results=$nRes"
  $deepOk = $nRes -ge 100
  $summary.Add("## 深度测试第4轮: exit=$deepExit 结果文件=$nRes")
  if ($deepOk) {
    python gen_report.py 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_gen_report_$stamp.log") -Encoding utf8
    Log "gen_report done"
    $summary.Add("## 汇总报告已生成: tools\_test_stat\汇总报告.md + 汇总表.csv")
  }
} else {
  Log "STEP 4: 深度测试跳过（快验证模式，-IncludeDeep 才跑 145 题）"
  $summary.Add("## 深度测试: 跳过（快验证模式；145 题请在打开 Codex 的会话内由「非高峰自动启动」执行）")
}

Log "STEP 5: chartOption 脚本级验证"
if ($IncludeDeep -and $deepOk) {
  node chart_check.mjs 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_chart_check_$stamp.log") -Encoding utf8
  $chartExit = $LASTEXITCODE
  Log "chart_check exit=$chartExit"
  $summary.Add("## chartOption 脚本级验证: exit=$chartExit (0=全过)")
} else { $summary.Add("## chartOption 脚本级验证: 跳过(未跑深度测试)") }

Log "STEP 6: 前端构建 + 浏览器实测"
Set-Location $root
Push-Location frontend
& npm.cmd run build 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_npm_build_$stamp.log") -Encoding utf8
$npmExit = $LASTEXITCODE
Pop-Location
Log "npm run build exit=$npmExit"
$summary.Add("## 前端构建 npm run build: exit=$npmExit")
if (-not (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue)) {
  Start-Process -WindowStyle Hidden -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev > frontend-dev.log 2>&1' -WorkingDirectory (Join-Path $root 'frontend')
  Start-Sleep -Seconds 5
}
$up5173 = Wait-Port 5173 180
Log "frontend 5173 up=$up5173"
if ($up5173) {
  Push-Location (Join-Path $root 'tools\_pwtest')
  node smoke_chart.cjs 2>&1 | Out-File -LiteralPath (Join-Path $logDir "_pwtest_$stamp.log") -Encoding utf8
  $pwExit = $LASTEXITCODE
  Pop-Location
  Log "pwtest exit=$pwExit"
  $summary.Add("## 浏览器实测(pwtest): exit=$pwExit 日志见 _pwtest_$stamp.log")
} else { $summary.Add("## 浏览器实测(pwtest): 跳过(前端 5173 未就绪)") }

$summary.Add("")
$summary.Add("> 生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$summary.Add("> 人工检查入口: tools\_test_stat\汇总表.csv (Excel 筛选「判定」列); 失败项对照 tools\_test_stat\results\{id}.json")
$summary.Add("> 已知遗留(与本次 stat_monthly 修复无关): AnalysisReportIntegrationTest 4例(execute 400+级联NPE)疑为 08-19 重建遗留; ai_model_config id=18 为测试残留假key(sk-不应入) 建议 status=0")
$summary.Add("> 本次修复: AnalysisPlanner 快照识别/排名最长匹配; RuleSqlGenerator 快照补 period 列; LlmSqlGenerator 快照走规则+规则11; TimeRangeParser 月末不再解析为近N月")
$summary.Add("> 任务脚本: tools\_test_stat\offpeak_run.ps1  本次日志: $logFile")
[System.IO.File]::WriteAllLines($summaryFile, $summary, (New-Object System.Text.UTF8Encoding($false)))
Log "=== 汇总已写入 $summaryFile ==="

$startHour = (Get-Date).Hour
if (-not $SkipShutdown -and $IncludeDeep -and $deepOk -and $startHour -ge 21) {
  Log "全部测试完成，2 分钟后自动关机（取消: shutdown /a）"
  shutdown /s /t 120 /c "错峰测试完成，即将关机。结果见 tools\_test_stat\错峰汇总-2026-08-20.md"
} elseif (-not $SkipShutdown) {
  Log "快验证/非关机时段，不自动关机（触发时刻 $startHour 点）；结果见 $summaryFile"
}
Log "=== 错峰任务结束 $stamp ==="
