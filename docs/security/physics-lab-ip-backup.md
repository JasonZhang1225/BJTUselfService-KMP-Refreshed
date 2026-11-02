# 物理实验 IP 备用路径

用户于 2026-11-02 根据老师通知授权增加 `http://59.64.5.228/`，保留 `http://wlsy.bjtu.edu.cn/` 主路径。用途仅为独立物理实验账号登录、读取已选实验，不包含选课或退课。

- 端点白名单：两个 origin 的 `/`（GET/登录 POST）及 `/Student/Teach/Course/CourseResult.aspx`（GET）。不跟随重定向；POST 只接受当前端点同源或站内相对跳转，随后读取结果验证会话。
- 主路径的登录页获取/表单检查失败或超过 6 秒时，尝试 IP；密码 POST、结果读取不在回退循环内，不重放密码。取消立即结束。
- 敏感数据：账号、密码、ASP.NET 表单状态、会话 Cookie、实验安排均经 HTTP 明文传输。沿用独立 Cookie jar，Cookie 按主机隔离；IP 不借用 MIS/教务会话，不把域名 Cookie 手动搬到 IP。设置页持续显示可信校园网与明文风险说明。
- Android 仅增加该 IP 的明文例外；iOS 保留 ATS 总体约束并登记该 IP 例外，不启用全局 NSAllowsArbitraryLoads。[Apple 文档](https://developer.apple.com/documentation/BundleResources/Information-Property-List/NSAppTransportSecurity/NSExceptionDomains) 明确 iOS 17+ 支持 IP 例外（本项目最低 iOS 18）；仍需运行验证，不能把 plist 编译成功当作网络成功。
- 未确认可替代的 HTTPS 地址，不绕过证书验证或将真实账号提交给第三方。2026-11-02 本机匿名 GET：域名 HTTP 404，IP HTTP 200 且登录字段与现有 ASP.NET 协议匹配。没有提交真实密码。
