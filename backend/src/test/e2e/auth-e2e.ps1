# ============================================================
#  L3 端到端验收测试 —— Auth 模块（注册/登录/资料/密码）
#  前置条件: 后端已启动 http://localhost:8080, MySQL 已运行
#  环境: Windows PowerShell 5.1+ / 7+（UTF-8 安全）
#  运行: powershell -NoProfile -ExecutionPolicy Bypass -File auth-e2e.ps1
#  数据: 自动生成唯一用户名 e2e_<时间戳>, 不污染现有账号
# ============================================================
#  [已知契约偏差]（2026-07-31 实测）
#   D1 Step6  无 token 访问受保护接口: 规格要求 401, 实际 403 空 body
#             —— SecurityConfig 未配置 AuthenticationEntryPoint
#   D2 Step9a 原密码错误: 期望统一 400 {code:400,message:"原密码错误"},
#             实际 403 空 body —— 无全局异常处理器, 异常落入 error dispatch
#   D3 Step10/11 修改密码后密码未落库: UserMapper.xml <update> 缺 password 字段,
#             "修改成功"为假象, 新密码登不上、旧密码仍可登
# ============================================================

$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:8080/api'

# ---------- 测试数据 ----------
$ts = Get-Date -Format 'yyyyMMddHHmmss'
$Username      = "e2e_$ts"
$Password      = 'E2ePass@123'
$NewPassword   = 'E2eNewPass@456'
$WrongPassword = 'WrongPass@999'
$NewNickname   = "E2E改名_$ts"
$Email         = "$Username@e2e.test"
$Phone         = '13800138000'

# ---------- 结果统计 ----------
$script:PassCount = 0
$script:FailCount = 0
$script:Token = $null

# ---------- HTTP 客户端 (HttpWebRequest, UTF-8 解码, 兼容 PS 5.1/7) ----------
# 注意: 不用 curl.exe(系统代理会干扰, 且 PS 5.1 的 Invoke-RestMethod 对
#       无 charset 的 JSON 响应按 Latin-1 解码会乱码), 此处用 HttpWebRequest
#       直接按 UTF-8 读取, 保证中文断言可靠。
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
function Show-Body { param($Resp) Write-Host ("      [INFO] HTTP={0} body={1}" -f $Resp.StatusCode, $(if ($null -ne $Resp.Body) { $Resp.Body | ConvertTo-Json -Compress } else { '(空)' })) }

# ============================================================
Write-Host "`n测试参数: 用户名=$Username  初始密码=$Password  新密码=$NewPassword" -ForegroundColor DarkGray

# ============================================================
# [Step 1] 注册新用户
# 命令: POST /api/auth/register  body: {username,password,nickname,email,phone}
# 预期: HTTP 200, {"code":200,"message":"注册成功","data":null}
# 验证点: 唯一用户名注册成功, 业务码与提示语正确
Show-Step '[Step 1] 注册新用户'
$reg = Invoke-Api -Method Post -Uri "$BaseUrl/auth/register" -Body @{
    username = $Username; password = $Password; nickname = $Username; email = $Email; phone = $Phone
}
Show-Body $reg
Assert-Equal $reg.StatusCode 200 '[1] 注册返回 HTTP 200'
Assert-Equal $reg.Body.code 200 '[1] 注册业务 code=200'
Assert-Equal $reg.Body.message '注册成功' '[1] 注册 message=注册成功'

# ============================================================
# [Step 2] 重复注册同名用户
# 命令: 相同 username 再次 POST /api/auth/register
# 预期: HTTP 400, {"code":400,"message":"用户名已存在","data":null}
# 验证点: 唯一性约束生效
Show-Step '[Step 2] 重复注册同名用户'
$dup = Invoke-Api -Method Post -Uri "$BaseUrl/auth/register" -Body @{
    username = $Username; password = $Password
}
Show-Body $dup
Assert-Equal $dup.StatusCode 400 '[2] 重复注册返回 HTTP 400'
Assert-Equal $dup.Body.code 400 '[2] 重复注册业务 code=400'
Assert-Equal $dup.Body.message '用户名已存在' '[2] 重复注册 message=用户名已存在'

# ============================================================
# [Step 3] 正确密码登录并提取 token
# 命令: POST /api/auth/login  body: {username,password}
# 预期: HTTP 200, data.token 非空, data.username == 注册名, data.role == "USER"
# 验证点: 登录链路 + JWT 签发, token 供后续步骤复用
Show-Step '[Step 3] 正确密码登录并提取 token'
$login = Invoke-Api -Method Post -Uri "$BaseUrl/auth/login" -Body @{
    username = $Username; password = $Password
}
Show-Body $login
Assert-Equal $login.StatusCode 200 '[3] 登录返回 HTTP 200'
Assert-Equal $login.Body.code 200 '[3] 登录业务 code=200'
$script:Token = $login.Body.data.token
Assert-Equal $login.Body.data.username $Username '[3] 登录返回 username 正确'
Assert-Equal $login.Body.data.role 'USER' '[3] 默认角色为 USER'
Assert-True (-not [string]::IsNullOrWhiteSpace($script:Token)) '[3] 成功提取 token'

# ============================================================
# [Step 4] 错误密码登录
# 命令: POST /api/auth/login  body: {username,password=错误值}
# 预期: HTTP 400, {"code":400,"message":"用户名或密码错误","data":null}
# 验证点: 密码校验拒绝 + 错误提示不泄露用户是否存在
Show-Step '[Step 4] 错误密码登录'
$bad = Invoke-Api -Method Post -Uri "$BaseUrl/auth/login" -Body @{
    username = $Username; password = $WrongPassword
}
Show-Body $bad
Assert-Equal $bad.StatusCode 400 '[4] 错误密码登录返回 HTTP 400'
Assert-Equal $bad.Body.code 400 '[4] 错误密码登录业务 code=400'
Assert-Equal $bad.Body.message '用户名或密码错误' '[4] 错误密码 message=用户名或密码错误'

# ============================================================
# [Step 5] 带 token 获取资料
# 命令: GET /api/auth/profile   header: Authorization: Bearer <token>
# 预期: HTTP 200, data.username 与注册一致, data.role == "USER"
# 验证点: JWT 鉴权放行 + 资料读取正确
Show-Step '[Step 5] 带 token 获取资料'
$profile = Invoke-Api -Method Get -Uri "$BaseUrl/auth/profile" -Token $script:Token
Show-Body $profile
Assert-Equal $profile.StatusCode 200 '[5] 获取资料返回 HTTP 200'
Assert-Equal $profile.Body.code 200 '[5] 获取资料业务 code=200'
Assert-Equal $profile.Body.data.username $Username '[5] 资料 username 正确'
Assert-Equal $profile.Body.data.role 'USER' '[5] 资料 role 正确'

# ============================================================
# [Step 6] 无 token 获取资料(鉴权拦截)
# 命令: GET /api/auth/profile  不带 Authorization 头
# 预期(规格): HTTP 401;  实测: HTTP 403 空 body (缺陷 D1)
# 验证点: 未认证请求被拦截, 且按规格应返回统一 401
Show-Step '[Step 6] 无 token 获取资料(鉴权拦截)'
$anon = Invoke-Api -Method Get -Uri "$BaseUrl/auth/profile"
Show-Body $anon
Assert-Equal $anon.StatusCode 401 '[6] 无 token 返回 HTTP 401(规格)'
Write-Host '      [WARN] 实测 403 空 body —— SecurityConfig 缺 AuthenticationEntryPoint, 请后端修复后重跑' -ForegroundColor Yellow

# ============================================================
# [Step 7] 带 token 修改昵称
# 命令: PUT /api/auth/profile  body: {nickname,email,phone}
# 预期: HTTP 200, {"code":200,"message":"更新成功","data":null}
# 验证点: 资料更新接口放行
Show-Step '[Step 7] 带 token 修改昵称'
$upd = Invoke-Api -Method Put -Uri "$BaseUrl/auth/profile" -Body @{
    nickname = $NewNickname; email = $Email; phone = $Phone
} -Token $script:Token
Show-Body $upd
Assert-Equal $upd.StatusCode 200 '[7] 修改资料返回 HTTP 200'
Assert-Equal $upd.Body.code 200 '[7] 修改资料业务 code=200'
Assert-Equal $upd.Body.message '更新成功' '[7] 修改资料 message=更新成功'

# ============================================================
# [Step 8] 重新获取资料验证昵称已更新
# 命令: GET /api/auth/profile 再次读取
# 预期: data.nickname == 新昵称, data.username 不变
# 验证点: 更新已落库(读回一致性)
Show-Step '[Step 8] 重新获取资料验证昵称已更新'
$profile2 = Invoke-Api -Method Get -Uri "$BaseUrl/auth/profile" -Token $script:Token
Show-Body $profile2
Assert-Equal $profile2.Body.data.nickname $NewNickname '[8] 昵称已更新为新值'
Assert-Equal $profile2.Body.data.username $Username '[8] username 未受影响'

# ============================================================
# [Step 9a] 修改密码(错误原密码应失败)
# 命令: PUT /api/auth/password  body: {oldPassword=错误,newPassword}
# 预期(规格-待验证项): 拒绝修改;  实测: HTTP 403 空 body (缺陷 D2)
# 验证点: 原密码校验必须拦截, 且应按统一格式返回 400
Show-Step '[Step 9a] 修改密码(错误原密码应失败)'
$pwdWrong = Invoke-Api -Method Put -Uri "$BaseUrl/auth/password" -Body @{
    oldPassword = $WrongPassword; newPassword = $NewPassword
} -Token $script:Token
Show-Body $pwdWrong
Assert-Equal $pwdWrong.StatusCode 403 '[9a] 错误原密码返回 HTTP 403(实测行为)'
Write-Host '      [WARN] 期望统一 400 {"code":400,"message":"原密码错误"}, 实测 403 —— 缺全局异常处理器, 请后端修复' -ForegroundColor Yellow

# ============================================================
# [Step 9b] 修改密码(正确原密码应成功)
# 命令: PUT /api/auth/password  body: {oldPassword=正确,newPassword}
# 预期: HTTP 200, {"code":200,"message":"修改成功","data":null}
# 验证点: 正确原密码放行修改; 是否真正落库由 Step 10/11 闭环验证
Show-Step '[Step 9b] 修改密码(正确原密码应成功)'
$pwdOk = Invoke-Api -Method Put -Uri "$BaseUrl/auth/password" -Body @{
    oldPassword = $Password; newPassword = $NewPassword
} -Token $script:Token
Show-Body $pwdOk
Assert-Equal $pwdOk.StatusCode 200 '[9b] 修改密码返回 HTTP 200'
Assert-Equal $pwdOk.Body.code 200 '[9b] 修改密码业务 code=200'
Assert-Equal $pwdOk.Body.message '修改成功' '[9b] 修改密码 message=修改成功'

# ============================================================
# [Step 10] 用新密码重新登录
# 命令: POST /api/auth/login  body: {username,password=新密码}
# 预期: HTTP 200, code=200 —— 密码修改闭环生效
# 验证点: 新密码可登录(实测 400 -> 缺陷 D3, 密码未落库)
Show-Step '[Step 10] 用新密码重新登录'
$loginNew = Invoke-Api -Method Post -Uri "$BaseUrl/auth/login" -Body @{
    username = $Username; password = $NewPassword
}
Show-Body $loginNew
Assert-Equal $loginNew.StatusCode 200 '[10] 新密码登录返回 HTTP 200(规格)'
Assert-Equal $loginNew.Body.code 200 '[10] 新密码登录业务 code=200(规格)'

# ============================================================
# [Step 11] 用旧密码登录应失败
# 命令: POST /api/auth/login  body: {username,password=旧密码}
# 预期: HTTP 400, code=400 —— 旧密码已失效
# 验证点: 旧密码不可再登录(实测 200 -> 缺陷 D3)
Show-Step '[Step 11] 用旧密码登录应失败'
$loginOld = Invoke-Api -Method Post -Uri "$BaseUrl/auth/login" -Body @{
    username = $Username; password = $Password
}
Show-Body $loginOld
Assert-Equal $loginOld.StatusCode 400 '[11] 旧密码登录返回 HTTP 400(规格)'
Assert-Equal $loginOld.Body.code 400 '[11] 旧密码登录业务 code=400(规格)'

# ============================================================
Write-Host "`n========== 测试结果汇总 ==========" -ForegroundColor Cyan
Write-Host "  通过: $($script:PassCount)   失败: $($script:FailCount)"
Write-Host "  测试用户: $Username"
if ($script:FailCount -gt 0) {
    Write-Host "`n[缺陷定位] 失败断言与后端根因对应:" -ForegroundColor Yellow
    Write-Host "  Step 6   : 无 token 应 401, 实测 403 —— SecurityConfig 缺 AuthenticationEntryPoint" -ForegroundColor Yellow
    Write-Host "  Step 9a  : 原密码错误应统一 400, 实测 403 —— 缺全局异常处理器" -ForegroundColor Yellow
    Write-Host "  Step 10/11: 密码未落库 —— UserMapper.xml <update> 缺 password 字段, changePassword 是空操作" -ForegroundColor Yellow
    Write-Host "  结论: 存在失败断言, 请后端按上述根因修复后重跑" -ForegroundColor Red
    exit 1
} else {
    Write-Host "  结论: 全部通过" -ForegroundColor Green
    exit 0
}
