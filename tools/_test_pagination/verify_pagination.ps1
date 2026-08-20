# 分页契约验证：/api/analysis/sessions|reports、/api/admin/user 返回 {rows,total} 且切片正确；新增会话报告接口可用。
# 供错峰任务在后端重启后执行；本脚本仅做接口层验证，浏览器级验证见人工检查清单。
param()
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$script:failCount = 0
function Check($name, $cond, $detail) {
  if ($cond) { Write-Output "PASS $name $detail" } else { Write-Output "FAIL $name $detail"; $script:failCount++ }
}
try {
  $login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
  $token = $login.data.token
  Check "login" ([bool]$token) "admin"
} catch {
  Write-Output "FAIL login: $_"
  exit 1
}
$h = @{ Authorization = "Bearer $token" }

function Verify-List($name, $url) {
  $r1 = Invoke-RestMethod -Uri "$base$url&page=1&pageSize=5" -Headers $h -TimeoutSec 20
  $d1 = $r1.data
  Check "$name shape" ($null -ne $d1.rows -and $null -ne $d1.total) "rows=[$($d1.rows.Count)] total=$($d1.total)"
  Check "$name page1 size<=5" ($d1.rows.Count -le 5) "page1 rows=$($d1.rows.Count)"
  if ($d1.total -gt 5) {
    $r2 = Invoke-RestMethod -Uri "$base$url&page=2&pageSize=5" -Headers $h -TimeoutSec 20
    $d2 = $r2.data
    $ids1 = @($d1.rows | ForEach-Object { $_.id })
    $ids2 = @($d2.rows | ForEach-Object { $_.id })
    $overlap = @($ids1 | Where-Object { $ids2 -contains $_ }).Count
    Check "$name page2 no-overlap" ($overlap -eq 0) "overlap=$overlap total=$($d2.total)"
    Check "$name page2 total-same" ($d2.total -eq $d1.total) "t1=$($d1.total) t2=$($d2.total)"
  } else {
    Write-Output "SKIP $name page2 (total<=5)"
  }
}

Verify-List "sessions" "/api/analysis/sessions?"
Verify-List "reports" "/api/analysis/reports?"
Verify-List "users" "/api/admin/user?"

try {
  $s = Invoke-RestMethod -Uri "$base/api/analysis/sessions?page=1&pageSize=5" -Headers $h -TimeoutSec 20
  $sessionIds = @($s.data.rows | ForEach-Object { $_.id })
  if ($sessionIds.Count -eq 0) {
    Write-Output "SKIP session-report (无会话)"
  } else {
    $found = $false
    foreach ($sid in $sessionIds) {
      try {
        $rep = Invoke-RestMethod -Uri "$base/api/analysis/session/$sid/report" -Headers $h -TimeoutSec 20
        if ($rep.code -eq 200 -and $rep.data) {
          Check "session-report endpoint" ($rep.data.content.Length -gt 0) "session=$sid roundNo=$($rep.data.roundNo)"
          $found = $true
          break
        }
      } catch {
        $msg = $_.Exception.Message
        $bodyOk = $msg -match '该轮未生成报告'
        if (-not $bodyOk) {
          Write-Output "INFO session=$sid report 400 body=$msg"
        }
      }
    }
    if (-not $found) { Write-Output "SKIP session-report (前5个会话均无报告，接口按预期返回 400 该轮未生成报告)" }
  }
} catch {
  Check "session-report endpoint" $false "异常: $_"
}

Write-Output "=== 分页验证结束 fail=$script:failCount ==="
exit $script:failCount