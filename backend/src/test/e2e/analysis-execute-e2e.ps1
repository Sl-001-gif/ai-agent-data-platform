# ============================================================
#  L3 端到端验收测试 —— SQL 执行与会话追踪（/api/analysis/execute）
#  前置条件: 后端已启动 http://localhost:8080, MySQL 已运行
#  环境: Windows PowerShell 5.1+ / 7+（UTF-8 安全，本文件需带 BOM）
#  运行: powershell -NoProfile -ExecutionPolicy Bypass -File analysis-execute-e2e.ps1
#  数据: 自动生成唯一用户名 e2e_exec_<时间戳>, 不污染现有账号
# ============================================================

$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:8080/api'
$Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MysqlArgs = @('-uroot', 'ai_agent_data')
$env:MYSQL_PWD = 'Admin@123456'

# ---------- 测试数据 ----------
$ts = Get-Date -Format 'yyyyMMddHHmmss'
$Username = "e2e_exec_$ts"
$Password = 'E2ePass@123'
$script:PassCount = 0
$script:FailCount = 0
$script:Token = $null

# ---------- 幂等准备演示表与测试数据（无数据才插） ----------
function Init-DemoData {
    $createSql = 'CREATE TABLE IF NOT EXISTS order_info (order_date DATE, region VARCHAR(50), channel VARCHAR(50), category VARCHAR(50), order_count INT, sales_amount DECIMAL(12,2), sales_volume INT); CREATE TABLE IF NOT EXISTS user_info (register_date DATE, age_group VARCHAR(50), city VARCHAR(50), new_user_count INT, active_user_count INT, retention_rate DECIMAL(5,2)); CREATE TABLE IF NOT EXISTS product_info (category VARCHAR(50), brand VARCHAR(50), sales_volume INT, sales_amount DECIMAL(12,2)); CREATE TABLE IF NOT EXISTS gov_info_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(500), doc_no VARCHAR(100), publish_unit VARCHAR(200), category VARCHAR(100), publish_date DATE, source_url VARCHAR(500) UNIQUE, summary TEXT, create_time DATETIME DEFAULT CURRENT_TIMESTAMP)'
    try { $createSql | & $Mysql @MysqlArgs 2>$null } catch { Write-Host "      [WARN] 建表: $($_.Exception.Message)" }
    try { $orderCount = (& $Mysql @MysqlArgs -N -e "SELECT COUNT(*) FROM order_info" 2>$null | Select-Object -First 1) } catch { $orderCount = $null }
    if ([string]::IsNullOrWhiteSpace($orderCount)) { $orderCount = '0' }
    if ([int]$orderCount -eq 0) {
        try {         & $Mysql @MysqlArgs -e "INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (CURDATE(),'East','Online','Mobile',120,120000.00,140),(DATE_SUB(CURDATE(), INTERVAL 1 DAY),'South','Offline','Appliances',80,90000.00,95),(DATE_SUB(CURDATE(), INTERVAL 2 DAY),'East','Offline','Mobile',60,66000.00,70)" 2>$null } catch { Write-Host "      [WARN] order_info 数据: $($_.Exception.Message)" }
        try {         & $Mysql @MysqlArgs -e "INSERT INTO user_info (register_date, age_group, city, new_user_count, active_user_count, retention_rate) VALUES (CURDATE(),'18-25','Changsha',300,900,45.00),(DATE_SUB(CURDATE(), INTERVAL 1 DAY),'26-35','Shaoyang',500,1500,52.00),(DATE_SUB(CURDATE(), INTERVAL 2 DAY),'36-45','Changsha',200,600,38.00)" 2>$null } catch { Write-Host "      [WARN] user_info 数据: $($_.Exception.Message)" }
        try {         & $Mysql @MysqlArgs -e "INSERT INTO product_info (category, brand, sales_volume, sales_amount) VALUES ('Mobile','BrandA',1500,1200000.00),('Appliances','BrandB',800,900000.00),('Food','BrandC',3000,300000.00)" 2>$null } catch { Write-Host "      [WARN] product_info 数据: $($_.Exception.Message)" }
        Write-Host "      [INFO] 演示表测试数据已初始化"
    } else {
        Write-Host "      [INFO] 演示表已有数据，跳过插入"
    }
    try { $govCount = (& $Mysql @MysqlArgs -N -e "SELECT COUNT(*) FROM gov_info_record" 2>$null | Select-Object -First 1) } catch { $govCount = $null }
    if ([string]::IsNullOrWhiteSpace($govCount) -or [int]$govCount -eq 0) {
        try { & $Mysql @MysqlArgs -e "INSERT INTO gov_info_record (title, doc_no, publish_unit, category, publish_date, source_url, summary) VALUES ('邵阳市政务公开年度报告','邵政发〔2026〕1号','邵阳市人民政府','工作动态',CURDATE(),'https://shaoyang.gov.cn/xxgk/1','摘要1'),('新宁县政务公开要点','新政办发〔2026〕2号','新宁县人民政府','规划计划',DATE_SUB(CURDATE(), INTERVAL 1 DAY),'https://xinning.gov.cn/xxgk/2','摘要2'),('邵阳市财政信息','邵财〔2026〕3号','邵阳市财政局','财政信息',DATE_SUB(CURDATE(), INTERVAL 2 DAY),'https://shaoyang.gov.cn/xxgk/3','摘要3'),('邵阳市安全生产通知','邵安〔2026〕4号','邵阳市应急管理局','通知公告',DATE_SUB(CURDATE(), INTERVAL 3 DAY),'https://shaoyang.gov.cn/xxgk/4','摘要4')" 2>$null } catch { Write-Host "      [WARN] gov 数据: $($_.Exception.Message)" }
        Write-Host "      [INFO] 政务演示数据已初始化"
    }
}

# ---------- HTTP 客户端 (HttpWebRequest, UTF-8 解码, 兼容 PS 5.1/7) ----------
function Invoke-Api {
    param([string]$Method, [string]$Uri, [object]$Body = $null, [string]$Token = $null)
    $req = [System.Net.HttpWebRequest]::Create($Uri)
    $req.Method = $Method
    $req.Timeout = 10000
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

# ---------- 断言工具 ----------
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
function Assert-Equal {
    param($Actual, $Expected, [string]$Name)
    Assert-True ($Actual -eq $Expected) $Name "期望=[$Expected] 实际=[$Actual]"
}
function Show-Step { param([string]$Title) Write-Host "`n=== $Title ===" -ForegroundColor Cyan }
function Show-Body { param($Resp) Write-Host ("      [INFO] HTTP={0} body={1}" -f $Resp.StatusCode, $(if ($null -ne $Resp.Body) { $Resp.Body | ConvertTo-Json -Compress -Depth 10 } else { '(空)' })) }

# ============================================================
# [Step 0] 幂等准备演示表与数据
# ============================================================
Show-Step '[Step 0] 准备演示表与测试数据'
Init-DemoData

# ============================================================
# [Step 1] 注册新用户
# 预期: HTTP 200, code=200
# ============================================================
Show-Step '[Step 1] 注册新用户'
$reg = Invoke-Api -Method Post -Uri "$BaseUrl/auth/register" -Body @{
    username = $Username; password = $Password; nickname = $Username
}
Show-Body $reg
Assert-Equal $reg.StatusCode 200 '[1] 注册返回 HTTP 200'
Assert-Equal $reg.Body.code 200 '[1] 注册业务 code=200'

# ============================================================
# [Step 2] 登录并提取 token
# 预期: HTTP 200, data.token 非空
# ============================================================
Show-Step '[Step 2] 登录并提取 token'
$login = Invoke-Api -Method Post -Uri "$BaseUrl/auth/login" -Body @{
    username = $Username; password = $Password
}
Show-Body $login
Assert-Equal $login.StatusCode 200 '[2] 登录返回 HTTP 200'
$script:Token = $login.Body.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($script:Token)) '[2] 成功提取 token'

# ============================================================
# [Step 3] 全链路执行闭环（7 条用例）
# 预期: HTTP 200, code=200, execution.rows 非空, chartType/sessionId 非空
# ============================================================
Show-Step '[Step 3] /api/analysis/execute 执行闭环'
$cases = @(
    @{ Text = '分析销售趋势' },
    @{ Text = '用户画像' },
    @{ Text = '商品类别排名' },
    @{ Text = '销售占比结构' },
    @{ Text = '整体情况' },
    @{ Text = '政务信息公开类目排名' },
    @{ Text = '政务信息发布趋势' },
    @{ Text = '各部门/单位发文量排名Top10' }
)
for ($i = 0; $i -lt $cases.Count; $i++) {
    $case = $cases[$i]
    $n = $i + 1
    $resp = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/execute" -Body @{ text = $case.Text } -Token $script:Token
    Show-Body $resp
    $execution = $resp.Body.data.execution
    $rows = @()
    if ($null -ne $execution) { $rows = @($execution.rows) }
    $chartType = [string]$resp.Body.data.chartType
    $sessionId = [string]$resp.Body.data.sessionId
    Assert-Equal $resp.StatusCode 200 "[3.$n] execute 返回 HTTP 200"
    Assert-Equal $resp.Body.code 200 "[3.$n] execute 业务 code=200"
    Assert-True ($rows.Count -gt 0) "[3.$n] execution.rows 非空"
    Assert-True (-not [string]::IsNullOrWhiteSpace($chartType)) "[3.$n] chartType 非空"
    Assert-True (-not [string]::IsNullOrWhiteSpace($sessionId)) "[3.$n] sessionId 非空"
    $interpretation = $resp.Body.data.interpretation
    $followups = @()
    if ($null -ne $resp.Body.data.followups) { $followups = @($resp.Body.data.followups) }
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$interpretation.text)) "[3.$n] interpretation.text 非空"
    Assert-True ($followups.Count -ge 2) "[3.$n] followups >= 2" "实际=$($followups.Count)"
}

# ============================================================
# [Step 3.5] 政务真实数据专项验证（3 个演示问题：趋势/分布/排名）
# 预期: 目标表=GOV_INFO_RECORD；规则模式下列数和=全表、排名单位非空
# ============================================================
Show-Step '[Step 3.5] 政务真实数据专项验证'
$govCases = @(
    @{ Text = '邵阳近3年按月发文量趋势'; Intent = 'SALES_TREND'; Chart = 'line' },
    @{ Text = '各公开类目发文量分布占比'; Intent = 'STRUCTURE'; Chart = 'pie' },
    @{ Text = '各部门/单位发文量排名Top10'; Intent = 'RANKING'; Chart = 'bar' }
)
for ($g = 0; $g -lt $govCases.Count; $g++) {
    $gc = $govCases[$g]
    $gn = $g + 1
    $resp = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/execute" -Body @{ text = $gc.Text } -Token $script:Token
    Show-Body $resp
    $execution = $resp.Body.data.execution
    $rows = @()
    if ($null -ne $execution) { $rows = @($execution.rows) }
    Assert-Equal $resp.StatusCode 200 "[3.5.$gn] execute 返回 HTTP 200"
    Assert-Equal $resp.Body.code 200 "[3.5.$gn] execute 业务 code=200"
    Assert-Equal $resp.Body.data.intent.intentType $gc.Intent "[3.5.$gn] 意图=$($gc.Intent)"
    Assert-Equal $resp.Body.data.plan.targetTable 'GOV_INFO_RECORD' "[3.5.$gn] 目标表=GOV_INFO_RECORD"
    Assert-Equal $resp.Body.data.chartType $gc.Chart "[3.5.$gn] 图表=$($gc.Chart)"
    Assert-True ($rows.Count -gt 0) "[3.5.$gn] execution.rows 非空"
    $genRow = (& $Mysql @MysqlArgs -N -e "SELECT output_data FROM analysis_step WHERE step_type='SQL' ORDER BY id DESC LIMIT 1" 2>$null | Select-Object -First 1)
    $isRule = $false
    if (-not [string]::IsNullOrWhiteSpace($genRow)) {
        try { $isRule = ([string](($genRow | ConvertFrom-Json).generatorType) -eq 'RULE') } catch { $isRule = $false }
    }
    if ($gc.Intent -eq 'SALES_TREND') {
        $first = $rows[0]
        Assert-True ($null -ne $first -and @($first.PSObject.Properties).Count -ge 2) "[3.5.$gn] 趋势行含月份/发文量字段" "实际字段数=$(@($first.PSObject.Properties).Count)"
    }
    if ($gc.Intent -eq 'STRUCTURE') {
        $sum = [long]0
        foreach ($r in $rows) {
            foreach ($prop in $r.PSObject.Properties) {
                if ($prop.Value -is [int] -or $prop.Value -is [long] -or $prop.Value -is [double]) { $sum += [long]$prop.Value }
            }
        }
        Assert-True ($sum -gt 0) "[3.5.$gn] 类目分布发文量之和>0" "实际=$sum"
        if ($isRule) {
            $govTotal = (& $Mysql @MysqlArgs -N -e "SELECT COUNT(*) FROM gov_info_record" 2>$null | Select-Object -First 1)
            if ([string]::IsNullOrWhiteSpace($govTotal)) { $govTotal = '0' }
            Assert-True ($sum -eq [int]$govTotal) "[3.5.$gn] 类目分布之和=全表($govTotal)（规则模式）" "实际=$sum"
        }
    }
    if ($gc.Intent -eq 'RANKING') {
        Assert-True ($rows.Count -le 10) "[3.5.$gn] 排名行数<=10" "实际=$($rows.Count)"
        if ($isRule) {
            $nonEmpty = 0
            foreach ($r in $rows) {
                $u = [string]$r.unit
                if ([string]::IsNullOrWhiteSpace($u)) { $u = [string]$r.publish_unit }
                if (-not [string]::IsNullOrWhiteSpace($u)) { $nonEmpty++ }
            }
            Assert-True ($nonEmpty -eq $rows.Count) "[3.5.$gn] 排名单位全部非空" "实际=$nonEmpty/$($rows.Count)"
        }
    }
}

# [Step 4] 无 token 访问 execute 应 401
# 预期: HTTP 401
# ============================================================
Show-Step '[Step 4] 无 token 访问 execute 应 401'
$anon = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/execute" -Body @{ text = '分析销售趋势' }
Show-Body $anon
Assert-Equal $anon.StatusCode 401 '[4] 无 token 返回 HTTP 401'

# ============================================================
# [Step 5] 带 token 空文本应 400（参数校验）
# 预期: HTTP 400
# ============================================================
Show-Step '[Step 5] 空文本应 400'
$blank = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/execute" -Body @{ text = '   ' } -Token $script:Token
Show-Body $blank
Assert-Equal $blank.StatusCode 400 '[5] 空文本返回 HTTP 400'

# ============================================================
# [Step 6] 报告生成（/api/analysis/report，覆盖式）
# 预期: HTTP 200, report.content 非空; 重复生成仍 1 行; REPORT 步骤落库; 缺参数 400; 无 token 401
# ============================================================
Show-Step '[Step 6] /api/analysis/report 报告生成'
$repResp = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/report" -Body @{ sessionId = [int64]$sessionId } -Token $script:Token
Show-Body $repResp
$report = $repResp.Body.data.report
Assert-Equal $repResp.StatusCode 200 '[6] report 返回 HTTP 200'
Assert-Equal $repResp.Body.code 200 '[6] report 业务 code=200'
Assert-True (-not [string]::IsNullOrWhiteSpace([string]$report.title)) '[6] report.title 非空'
Assert-True (-not [string]::IsNullOrWhiteSpace([string]$report.content)) '[6] report.content 非空'
Assert-True ($report.generatorType -eq 'LLM' -or $report.generatorType -eq 'RULE') '[6] generatorType 为 LLM 或 RULE'

$repResp2 = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/report" -Body @{ sessionId = [int64]$sessionId } -Token $script:Token
Assert-Equal $repResp2.StatusCode 200 '[6] 重复生成返回 HTTP 200'
$repRowCount = (& $Mysql @MysqlArgs -N -e "SELECT COUNT(*) FROM analysis_report WHERE session_id = $sessionId" 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($repRowCount)) { $repRowCount = '0' }
Assert-True ([int]$repRowCount -eq 1) '[6] 覆盖式：analysis_report 仅 1 行' "实际=$repRowCount"
$repStepCount = (& $Mysql @MysqlArgs -N -e "SELECT COUNT(*) FROM analysis_step WHERE session_id = $sessionId AND step_type='REPORT'" 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($repStepCount)) { $repStepCount = '0' }
Assert-True ([int]$repStepCount -ge 1) '[6] REPORT 步骤已落库' "实际=$repStepCount"

$repMissing = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/report" -Body @{ } -Token $script:Token
Assert-Equal $repMissing.StatusCode 400 '[6] 缺 sessionId 返回 HTTP 400'

$repAnon = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/report" -Body @{ sessionId = [int64]$sessionId }
Assert-Equal $repAnon.StatusCode 401 '[6] 无 token 返回 HTTP 401'

# ============================================================
# 汇总输出与退出码
# ============================================================
Write-Host "`n========== 测试结果汇总 ==========" -ForegroundColor Cyan
Write-Host "  通过: $($script:PassCount)   失败: $($script:FailCount)"
Write-Host "  测试用户: $Username"
if ($script:FailCount -gt 0) {
    Write-Host "`n[失败详情] 请根据上方 FAIL 行定位失败步骤" -ForegroundColor Yellow
    exit 1
} else {
    Write-Host "  结论: 全部通过" -ForegroundColor Green
    exit 0
}