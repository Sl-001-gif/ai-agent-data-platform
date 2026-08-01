# ============================================================
#  L3 验收脚本 —— 真实 LLM 链路验证（/api/analysis/execute）
#  前置条件:
#    1) 后端已启动 http://localhost:8080（若要看 LLM 生效，
#       后端必须在设置了 $env:AI_API_KEY 的同一个终端启动）
#    2) MySQL80 运行中
#  运行: powershell -NoProfile -ExecutionPolicy Bypass -File llm-verify-e2e.ps1
#  通过标准: generatorType=LLM 且 execution.rowCount > 0
#  若输出 generatorType=RULE: key 未读到或 LLM 两次校验失败回退规则，
#  请确认后端是在设了 AI_API_KEY 的终端启动的
# ============================================================
$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:8080/api'
$Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$env:MYSQL_PWD = 'Admin@123456'

$ts = Get-Date -Format 'yyyyMMddHHmmss'
$Username = "llm_$ts"
$Password = 'LlmPass@123'
$script:PassCount = 0
$script:FailCount = 0

function Invoke-Api {
    param([string]$Method, [string]$Uri, [object]$Body = $null, [string]$Token = $null)
    $req = [System.Net.HttpWebRequest]::Create($Uri)
    $req.Method = $Method
    $req.Timeout = 120000
    $req.ContentType = 'application/json; charset=utf-8'
    if ($Token) { $req.Headers['Authorization'] = "Bearer $Token" }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $req.ContentLength = $bytes.Length
        $rs = $req.GetRequestStream()
        $rs.Write($bytes, 0, $bytes.Length)
        $rs.Close()
    }
    $resp = $null
    try { $resp = $req.GetResponse() }
    catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return [pscustomobject]@{ StatusCode = -1; Body = $null } }
    }
    $status = [int]$resp.StatusCode
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $content = $reader.ReadToEnd()
    $reader.Close()
    $resp.Close()
    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($content)) {
        try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $content }
    }
    return [pscustomobject]@{ StatusCode = $status; Body = $parsed }
}

function Assert-True {
    param([bool]$Condition, [string]$Name, [string]$Detail = '')
    if ($Condition) {
        Write-Host "      [PASS] $Name" -ForegroundColor Green
        $script:PassCount++
    } else {
        Write-Host "      [FAIL] $Name  $Detail" -ForegroundColor Red
        $script:FailCount++
    }
}

Write-Host "=== [1] 注册新用户 $Username ===" -ForegroundColor Cyan
$reg = Invoke-Api -Method Post -Uri "$BaseUrl/auth/register" -Body @{ username = $Username; password = $Password; nickname = $Username }
Assert-True ($reg.StatusCode -eq 200) '[1] 注册 HTTP 200' "实际=$($reg.StatusCode)"

Write-Host "`n=== [2] 登录并提取 token ===" -ForegroundColor Cyan
$login = Invoke-Api -Method Post -Uri "$BaseUrl/auth/login" -Body @{ username = $Username; password = $Password }
Assert-True ($login.StatusCode -eq 200) '[2] 登录 HTTP 200' "实际=$($login.StatusCode)"
$token = $login.Body.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($token)) '[2] token 非空'

Write-Host "`n=== [3] 执行政务分析: 政务信息发布趋势 ===" -ForegroundColor Cyan
$resp = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/execute" -Body @{ text = '政务信息发布趋势' } -Token $token
Assert-True ($resp.StatusCode -eq 200) '[3] execute HTTP 200' "实际=$($resp.StatusCode)"
if ($resp.StatusCode -ne 200 -and $null -ne $resp.Body) { Write-Host ("      [INFO] 后端返回: {0}" -f ($resp.Body | ConvertTo-Json -Compress -Depth 8)) }
$data = $resp.Body.data
$sql = [string]$data.sql
$rowCount = 0
if ($null -ne $data.execution) { $rowCount = [int]$data.execution.rowCount }
$chartType = [string]$data.chartType
$targetTable = [string]$data.plan.targetTable
Write-Host ("      [INFO] 目标表={0} 图表={1} 行数={2}" -f $targetTable, $chartType, $rowCount)
Write-Host ("      [INFO] SQL: {0}" -f $sql)
Assert-True ($targetTable -eq 'GOV_INFO_RECORD') '[3] 目标表=GOV_INFO_RECORD'
Assert-True ($rowCount -gt 0) '[3] execution.rowCount>0' "实际=$rowCount"

Write-Host "`n=== [4] 查库确认生成器类型 generatorType ===" -ForegroundColor Cyan
$outRow = (& $Mysql -uroot ai_agent_data -N -e "SELECT output_data FROM analysis_step WHERE step_type='SQL' ORDER BY id DESC LIMIT 1" 2>$null | Select-Object -First 1)
$genType = ''
if (-not [string]::IsNullOrWhiteSpace($outRow)) {
    try { $genType = [string]($outRow | ConvertFrom-Json).generatorType } catch { $genType = '' }
}
Write-Host ("      [INFO] generatorType = {0}" -f $genType)
Assert-True ($genType -eq 'LLM') '[4] generatorType=LLM（真实 LLM 生效）' "实际=$genType（RULE=未配 key 或回退兜底）"


$sessionId = [string]$data.sessionId
Write-Host "`n=== [5] 解读与追问（interpretation/followups）===" -ForegroundColor Cyan
$interpText = [string]$data.interpretation.text
$followups = @()
if ($null -ne $data.followups) { $followups = @($data.followups) }
Assert-True (-not [string]::IsNullOrWhiteSpace($interpText)) '[5] interpretation.text 非空'
Assert-True ($followups.Count -ge 2) '[5] followups >= 2' "实际=$($followups.Count)"

Write-Host "`n=== [6] 查库确认 INTERPRET 步骤 generatorType=LLM ===" -ForegroundColor Cyan
$interpRow = (& $Mysql -uroot ai_agent_data -N -e "SELECT output_data FROM analysis_step WHERE session_id =  AND step_type='INTERPRET' ORDER BY id DESC LIMIT 1" 2>$null | Select-Object -First 1)
$interpGenType = ''
if (-not [string]::IsNullOrWhiteSpace($interpRow)) {
    try { $interpGenType = [string](($interpRow | ConvertFrom-Json).interpretation.generatorType) } catch { $interpGenType = '' }
}
Assert-True ($interpGenType -eq 'LLM') '[6] INTERPRET generatorType=LLM（真实 LLM 生效）' "实际=$interpGenType"
Write-Host "`n=== [7] 报告生成（真实 LLM 链路）===" -ForegroundColor Cyan
$repResp = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/report" -Body @{ sessionId = [int64]$sessionId } -Token $token
Assert-True ($repResp.StatusCode -eq 200) '[7] report HTTP 200' "实际=$($repResp.StatusCode)"
$report = $repResp.Body.data.report
Assert-True (-not [string]::IsNullOrWhiteSpace([string]$report.content)) '[7] report.content 非空'
Assert-True ($report.generatorType -eq 'LLM') '[7] report generatorType=LLM（真实 LLM 生效）' "实际=$($report.generatorType)"
$repDbRow = (& $Mysql -uroot ai_agent_data -N -e "SELECT COUNT(*) FROM analysis_report WHERE session_id = $sessionId" 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($repDbRow)) { $repDbRow = '0' }
Assert-True ([int]$repDbRow -eq 1) '[7] analysis_report 已落库 1 行（覆盖式）' "实际=$repDbRow"
$repStep = (& $Mysql -uroot ai_agent_data -N -e "SELECT COUNT(*) FROM analysis_step WHERE session_id = $sessionId AND step_type='REPORT'" 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($repStep)) { $repStep = '0' }
Assert-True ([int]$repStep -ge 1) '[7] REPORT 步骤已落库' "实际=$repStep"

Write-Host "`n========== 结果汇总 ==========" -ForegroundColor Cyan
Write-Host "  通过: $($script:PassCount)   失败: $($script:FailCount)"
if ($script:FailCount -gt 0) {
    Write-Host "  提示: 若 [4]/[6]/[7] 失败，说明 key 未生效——请确认后端是在设了 `$env:AI_API_KEY 的终端启动的，或 key 无效" -ForegroundColor Yellow
    exit 1
} else {
    Write-Host "  结论: LLM 链路真实生效，全部通过" -ForegroundColor Green
    exit 0
}
