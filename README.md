# TeslaCarWithinBridge (Root Android + 内置 Caddy)

专为 **特斯拉车机浏览器 + iPhone CarWithin + Root 安卓热点** 设计的轻量级安卓工具。

## 核心功能
- **Root 权限 `iptables` 劫持**：自动将特斯拉发往 `100.64.0.1:8443`（CGNAT 非 RFC 1918 地址，绕过特斯拉内网黑名单）的流量重定向至本机 Caddy，停止时自动清理规则。
- **内置 Caddy（含 `caddy-dns/cloudflare` 插件）**：打包为 `jniLibs/arm64-v8a/libcaddy.so`，自动通过 Cloudflare DNS-01 向 Let's Encrypt 申请并续期 `tesla.shzxm.com` 正规 HTTPS 证书，同时原生透传 WebSocket 反向代理到主 iPhone 的 CarWithin（`9888` 端口）。
- **一键更新 Cloudflare DNS**：调用 Cloudflare API 自动将 `tesla.shzxm.com` 的 A 记录指向 `100.64.0.1`（自动关闭小黄云）。
- **自动扫描热点设备**：通过 Root 执行 `ip neigh` 识别连入安卓热点的主 iPhone IP，一键填入。
- **前台保活服务**：锁屏后台运行不断连。
