# ============================================================
#  L3 端到端验收测试 —— Text-to-SQL 最小闭环（/api/analysis/sql）
#  前置条件: 后端已启动 http://localhost:8080, MySQL 已运行
#  环境: Windows PowerShell 5.1+ / 7+（UTF-8 安全，本文件需带 BOM）
#  运行: powershell -NoProfile -ExecutionPolicy Bypass -File analysis-sql-e2e.ps1
#  数据: 自动生成唯一用户名 e2e_sql_<时间戳>, 不污染现有账号
# ============================================================

$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:8080/api'

# ---------- 测试数据 ----------
$ts = Get-Date -Format 'yyyyMMddHHmmss'
$Username = "e2e_sql_$ts"
$Password = 'E2ePass@123'
$script:PassCount = 0
$script:FailCount = 0
$script:Token = $null

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

# SQL 黑名单关键字正则（大小写不敏感, 完整词匹配）
$BlacklistPattern = '(?i)\b(drop|update|delete|insert|alter|truncate|create|replace|grant|revoke|set|exec|execute|call|explain|show|use|merge|load|handler|do|kill|rename|start|stop|commit|rollback|savepoint|lock|unlock|purge|reset|install|uninstall|union|into)\b'

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
# [Step 3] Text-to-SQL 闭环（5 类意图）
# 预期: HTTP 200, code=200, intentType 正确, sql 合法且校验通过
# ============================================================
Show-Step '[Step 3] /api/analysis/sql 意图 → SQL 生成与校验'
$cases = @(
    @{ Text = '分析最近30天的销售趋势'; Type = 'SALES_TREND' },
    @{ Text = '看看我们的用户画像'; Type = 'USER_PROFILE' },
    @{ Text = '对比华东和华南的销售额'; Type = 'COMPARISON' },
    @{ Text = '各品类销售占比'; Type = 'STRUCTURE' },
    @{ Text = '哪个商品卖得最好，排个Top10'; Type = 'RANKING' }
)
for ($i = 0; $i -lt $cases.Count; $i++) {
    $case = $cases[$i]
    $n = $i + 1
    $resp = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/sql" -Body @{ text = $case.Text } -Token $script:Token
    Show-Body $resp
    $sql = [string]$resp.Body.data.sql
    Assert-Equal $resp.StatusCode 200 "[3.$n] sql 返回 HTTP 200"
    Assert-Equal $resp.Body.code 200 "[3.$n] sql 业务 code=200"
    Assert-Equal $resp.Body.data.intent.intentType $case.Type "[3.$n] 意图类型=$($case.Type)"
    Assert-True ($sql -match '(?i)^\s*select\b') "[3.$n] sql 以 SELECT 开头" "实际=[$sql]"
    Assert-True (-not $sql.Contains(';')) "[3.$n] sql 不含分号"
    Assert-True (-not $sql.Contains('--')) "[3.$n] sql 不含 -- 注释"
    Assert-True (-not $sql.Contains('/*')) "[3.$n] sql 不含 /* 注释"
    Assert-True ($sql -notmatch $BlacklistPattern) "[3.$n] sql 不含黑名单关键字"
    Assert-True ($resp.Body.data.validation.valid -eq $true) "[3.$n] validation.valid=true"
    Assert-True (($resp.Body.data.validation.errors | Measure-Object).Count -eq 0) "[3.$n] validation.errors 为空"
}

# ============================================================
# [Step 4] 无 token 访问 sql 应 401
# 预期: HTTP 401
# ============================================================
Show-Step '[Step 4] 无 token 访问 sql 应 401'
$anon = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/sql" -Body @{ text = '分析销售趋势' }
Show-Body $anon
Assert-Equal $anon.StatusCode 401 '[4] 无 token 返回 HTTP 401'

# ============================================================
# [Step 5] 带 token 空文本应 400（参数校验）
# 预期: HTTP 400
# ============================================================
Show-Step '[Step 5] 空文本应 400'
$blank = Invoke-Api -Method Post -Uri "$BaseUrl/analysis/sql" -Body @{ text = '   ' } -Token $script:Token
Show-Body $blank
Assert-Equal $blank.StatusCode 400 '[5] 空文本返回 HTTP 400'

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