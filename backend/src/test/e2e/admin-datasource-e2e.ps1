# ============================================================
#  L3 端到端验收测试 —— 管理端数据源管理（/api/admin/datasource）
#  前置条件: 后端已启动 http://localhost:8080, MySQL 已运行
#  环境: Windows PowerShell 5.1+ / 7+（UTF-8 安全，本文件需带 BOM）
#  运行: powershell -NoProfile -ExecutionPolicy Bypass -File admin-datasource-e2e.ps1
#  数据: 自动生成唯一用户名, 管理员用临时提升的测试用户, 不污染现有账号
# ============================================================

$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:8080/api'
$Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MysqlArgs = @('-uroot', 'ai_agent_data')
$env:MYSQL_PWD = 'Admin@123456'

# ---------- 测试数据 ----------
$ts = Get-Date -Format 'yyyyMMddHHmmss'
$AdminUser = "adm_e2e_$ts"
$NormalUser = "usr_e2e_$ts"
$Password = 'E2ePass@123'
$DsName = "e2e数据源$ts"
$script:PassCount = 0
$script:FailCount = 0
$script:AdminToken = $null
$script:NormalToken = $null
$script:CreatedId = $null

# ---------- 幂等准备表 ----------
function Init-Table {
    $createSql = 'CREATE TABLE IF NOT EXISTS ai_data_source (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, db_type VARCHAR(20) DEFAULT ''MYSQL'', host VARCHAR(100) NOT NULL, port INT NOT NULL DEFAULT 3306, database_name VARCHAR(100) NOT NULL, username VARCHAR(100) NOT NULL, password VARCHAR(200) NOT NULL, remark VARCHAR(255), create_by BIGINT, create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
    try { $createSql | & $Mysql @MysqlArgs 2>$null } catch { Write-Host "      [WARN] 建表: $($_.Exception.Message)" }
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
# [Step 0] 幂等准备 ai_data_source 表
# ============================================================
Show-Step '[Step 0] 准备数据源表'
Init-Table
Write-Host '      [INFO] ai_data_source 表已就绪'

# ============================================================
# [Step 1] 注册管理员测试用户 + 提升角色 + 登录
# 预期: HTTP 200, 库中 role=ADMIN, token 非空
# ============================================================
Show-Step '[Step 1] 注册管理员测试用户并提升角色'
$reg = Invoke-Api -Method POST -Uri "$BaseUrl/auth/register" -Body @{
    username = $AdminUser; password = $Password; nickname = $AdminUser
}
Show-Body $reg
Assert-Equal $reg.StatusCode 200 '[1] 管理员注册返回 HTTP 200'
Assert-Equal $reg.Body.code 200 '[1] 管理员注册业务 code=200'

try {
    & $Mysql @MysqlArgs -e "UPDATE sys_user SET role='ADMIN' WHERE username='$AdminUser'" 2>$null
    $role = (& $Mysql @MysqlArgs -N -e "SELECT role FROM sys_user WHERE username='$AdminUser'" 2>$null | Select-Object -First 1)
} catch { $role = $null }
Assert-True ($role -eq 'ADMIN') '[1] 库中角色已提升为 ADMIN' "实际=[$role]"

$login = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body @{
    username = $AdminUser; password = $Password
}
Show-Body $login
Assert-Equal $login.StatusCode 200 '[1] 管理员登录返回 HTTP 200'
$script:AdminToken = $login.Body.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($script:AdminToken)) '[1] 管理员 token 提取成功'

# ============================================================
# [Step 2] 无 token 访问 -> 401
# ============================================================
Show-Step '[Step 2] 未登录访问返回 401'
$anon = Invoke-Api -Method GET -Uri "$BaseUrl/admin/datasource"
Show-Body $anon
Assert-Equal $anon.StatusCode 401 '[2] 无 token 返回 HTTP 401'

# ============================================================
# [Step 3] 普通用户访问 -> 403
# ============================================================
Show-Step '[Step 3] 普通用户访问返回 403'
$reg2 = Invoke-Api -Method POST -Uri "$BaseUrl/auth/register" -Body @{
    username = $NormalUser; password = $Password; nickname = $NormalUser
}
Assert-Equal $reg2.StatusCode 200 '[3] 普通用户注册返回 HTTP 200'
$login2 = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body @{
    username = $NormalUser; password = $Password
}
$script:NormalToken = $login2.Body.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($script:NormalToken)) '[3] 普通用户 token 提取成功'
$forbidden = Invoke-Api -Method GET -Uri "$BaseUrl/admin/datasource" -Token $script:NormalToken
Show-Body $forbidden
Assert-Equal $forbidden.StatusCode 403 '[3] 普通用户返回 HTTP 403'

# ============================================================
# [Step 4] 新增数据源
# 预期: HTTP 200, data.id 非空
# ============================================================
Show-Step '[Step 4] 新增数据源'
$payload = @{
    name = $DsName; dbType = 'MYSQL'; host = 'localhost'; port = 3306
    databaseName = 'ai_agent_data'; username = 'root'; password = 'Admin@123456'; remark = 'L3测试'
}
$create = Invoke-Api -Method POST -Uri "$BaseUrl/admin/datasource" -Body $payload -Token $script:AdminToken
Show-Body $create
Assert-Equal $create.StatusCode 200 '[4] 新增返回 HTTP 200'
Assert-Equal $create.Body.code 200 '[4] 新增业务 code=200'
$script:CreatedId = $create.Body.data.id
Assert-True ($null -ne $script:CreatedId -and $script:CreatedId -gt 0) '[4] 返回 data.id 非空' "id=[$($script:CreatedId)]"

# ============================================================
# [Step 5] 列表包含新增数据源
# ============================================================
Show-Step '[Step 5] 列表包含新增数据源'
$list = Invoke-Api -Method GET -Uri "$BaseUrl/admin/datasource" -Token $script:AdminToken
$names = @($list.Body.data | ForEach-Object { $_.name })
Assert-Equal $list.StatusCode 200 '[5] 列表返回 HTTP 200'
Assert-True ($names -contains $DsName) '[5] 列表包含新增数据源'

# ============================================================
# [Step 6] 连接测试成功（本机 MySQL）
# ============================================================
Show-Step '[Step 6] 连接测试成功'
$testOk = Invoke-Api -Method POST -Uri "$BaseUrl/admin/datasource/test" -Body $payload -Token $script:AdminToken
Show-Body $testOk
Assert-Equal $testOk.StatusCode 200 '[6] 测试返回 HTTP 200'
Assert-True ($testOk.Body.data.success -eq $true) '[6] 本机 MySQL 连接成功'

# ============================================================
# [Step 7] 连接测试失败（错误密码）返回原因不抛错
# ============================================================
Show-Step '[Step 7] 连接测试失败返回原因'
$badPayload = @{
    name = '失败测试'; dbType = 'MYSQL'; host = 'localhost'; port = 3306
    databaseName = 'ai_agent_data'; username = 'root'; password = 'WrongPass123'; remark = ''
}
$testFail = Invoke-Api -Method POST -Uri "$BaseUrl/admin/datasource/test" -Body $badPayload -Token $script:AdminToken
Show-Body $testFail
Assert-Equal $testFail.StatusCode 200 '[7] 失败测试返回 HTTP 200（不抛错）'
Assert-True ($testFail.Body.data.success -eq $false) '[7] data.success=false'
Assert-True (-not [string]::IsNullOrWhiteSpace($testFail.Body.data.message)) '[7] 返回失败原因 message 非空'

# ============================================================
# [Step 8] 更新数据源
# ============================================================
Show-Step '[Step 8] 更新数据源'
$updatePayload = @{
    name = "${DsName}改"; dbType = 'MYSQL'; host = 'localhost'; port = 3306
    databaseName = 'ai_agent_data'; username = 'root'; password = 'Admin@123456'; remark = '已更新'
}
$update = Invoke-Api -Method PUT -Uri "$BaseUrl/admin/datasource/$($script:CreatedId)" -Body $updatePayload -Token $script:AdminToken
Show-Body $update
Assert-Equal $update.StatusCode 200 '[8] 更新返回 HTTP 200'
Assert-Equal $update.Body.code 200 '[8] 更新业务 code=200'
$list2 = Invoke-Api -Method GET -Uri "$BaseUrl/admin/datasource" -Token $script:AdminToken
$names2 = @($list2.Body.data | ForEach-Object { $_.name })
Assert-True ($names2 -contains "${DsName}改") '[8] 列表包含更新后的名称'

# ============================================================
# [Step 9] 删除数据源
# ============================================================
Show-Step '[Step 9] 删除数据源'
$del = Invoke-Api -Method DELETE -Uri "$BaseUrl/admin/datasource/$($script:CreatedId)" -Token $script:AdminToken
Show-Body $del
Assert-Equal $del.StatusCode 200 '[9] 删除返回 HTTP 200'
Assert-Equal $del.Body.code 200 '[9] 删除业务 code=200'
$list3 = Invoke-Api -Method GET -Uri "$BaseUrl/admin/datasource" -Token $script:AdminToken
$names3 = @($list3.Body.data | ForEach-Object { $_.name })
Assert-True ($names3 -notcontains "${DsName}改") '[9] 列表不再包含已删除数据源'

# ============================================================
# [Step 10] 缺字段新增 -> 400
# ============================================================
Show-Step '[Step 10] 缺字段新增返回 400'
$empty = Invoke-Api -Method POST -Uri "$BaseUrl/admin/datasource" -Body @{} -Token $script:AdminToken
Show-Body $empty
Assert-Equal $empty.StatusCode 400 '[10] 缺字段返回 HTTP 400'
Assert-Equal $empty.Body.code 400 '[10] 缺字段业务 code=400'

# ============================================================
# 汇总
# ============================================================
Write-Host "`n============================================"
Write-Host "管理端数据源管理 E2E: 通过 $($script:PassCount) 项, 失败 $($script:FailCount) 项"
Write-Host "============================================"
if ($script:FailCount -gt 0) { exit 1 } else { exit 0 }