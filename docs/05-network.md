# 五、内网穿透：为什么最后选了 SakuraFrp

## 问题

小主机在家里/学校内网，没有公网 IP。手机在外面用流量时要连进来。

校园网 / 家宽基本都是**共享 NAT**，运营商不给端口映射。实测：

```
# 从内网打自己的公网 IP
curl https://api.ipify.org     → 36.49.66.128
nc -zv 36.49.66.128 25565      → 超时
nc -zv 36.49.66.128 19132      → 超时
```

出口 IP 是共享的，端口根本没转给你。

## 方案对比

| 方案 | MC 能用吗 | 国内延迟 | 免费额度 | 结论 |
|---|---|---|---|---|
| **Cloudflare Tunnel** | ❌ 只吃 HTTP/HTTPS | — | 无限 | MC 是裸 TCP/UDP，用不了 |
| Cloudflare Spectrum | ✅ TCP/UDP | 一般 | 无免费 | 企业套餐才有 |
| **Tailscale** | ✅ 全协议 | 优秀 | 100 设备 | 但**要求对方也装** |
| ngrok | ✅ TCP | 差（海外） | 1 隧道 | 延迟高，免费版限速 |
| playit.gg | ✅ 全协议 | 差（海外） | 有 | 国内延迟 100–200ms |
| **SakuraFrp** | ✅ UDP/TCP | **优秀（国内节点）** | 有 | ← **选它** |

### 为什么 Cloudflare Tunnel 不行

这是个常见误解。Tunnel 的工作原理是「在公网边缘终止 HTTP，再回源到内网」。它：

- 只接受 HTTP/HTTPS 流量（`http://` 或 `https://` service）
- MC 的 Java 版是裸 **TCP**，基岩版是裸 **UDP**
- 隧道配置里写 `service: tcp://...` 会直接报错

用 SRV 记录也不行 —— SRV 只是 DNS 地址簿，**不转发流量**。

Cloudflare Spectrum 确实支持任意 TCP/UDP，但那是企业套餐（Enterprise），个人用不起。

### 为什么不选 Tailscale

Tailscale 技术上完全可行，延迟还最好（P2P 直连 1.5–2ms）。但它有个致命前提：

> **连接的两端都必须装 Tailscale 并登录同一账号。**

你的手机可以装，但：

- 来玩的朋友装不了（他们不在你的 tailnet 里）
- 每次开服要确认手机上的 Tailscale 是连着的
- iOS 上 Tailscale 还经常被系统杀掉后台

所以 Tailscale 适合「自己两台设备之间」，不适合「给手机/朋友开服」。

### 为什么 SakuraFrp

实测国内可达性（在校园网的 AM01 上）：

```bash
https://www.natfrp.com   → 200   0.11 秒   ← 快得离谱
https://www.baidu.com    → 200   0.17 秒
https://gitee.com        → 200   1.56 秒
https://github.com       → 200   8.00 秒   ← 慢，还超时
```

`natfrp.com` 比 GitHub 快 70 倍以上，**就是本机速度**。下载 7MB 的 frpc 只要 **1.4 秒**。

对比 playit.gg：从 GitHub 下载 300 秒超时，文件还残缺（`missing section headers at 6229984`），跑起来段错误 `rc=139`。

---

## SakuraFrp 配置步骤

### 1. 注册并创建隧道

1. 访问 https://www.natfrp.com ，注册（免费版需要实名认证，国内规定）
2. 用户中心 → 隧道 → 新建隧道
3. 配置：
   - 隧道类型：**UDP**
   - 本地地址：`127.0.0.1`
   - 本地端口：`19132`（Geyser 的 RakNet 端口）
   - 节点：选负载低的（会自动分配）
4. 复制**访问密钥**，形如 `xxxxx:yyyyy`

### 2. 下载 frpc

```bash
# 国内镜像，1.4 秒下完 7MB
BASE="https://nya.globalslb.net/natfrp/client/frpc/0.51.0-sakura-15.1"
curl -fsSL -o frpc "$BASE/frpc_linux_amd64"
chmod +x frpc
./frpc -v
# 0.51.0-sakura-15.1
```

镜像目录结构：

```
https://nya.globalslb.net/natfrp/client/
├── frpc/
│   ├── 0.51.0-sakura-15.1/
│   │   ├── frpc_linux_amd64
│   │   ├── frpc_linux_arm64
│   │   └── frpc_windows_amd64.exe
│   └── 0.51.0-sakura-15/
├── launcher-windows/
├── launcher-unix/
└── ...
```

### 3. 密钥单独存文件

**不要**把密钥写在命令行或 systemd unit 里 —— 会出现在 `ps` 输出和日志里。

```bash
printf '%s' "xxxxx:yyyyy" > /home/user/natfrp/token.txt
chmod 600 /home/user/natfrp/token.txt
```

### 4. systemd 服务

```ini
[Unit]
Description=SakuraFrp frpc (Minecraft Bedrock UDP tunnel)
After=network-online.target viaproxy.service
Wants=network-online.target

[Service]
Type=simple
User=linocean
WorkingDirectory=/home/linocean/natfrp
ExecStart=/bin/sh -c '/home/linocean/natfrp/frpc -f "$(cat /home/linocean/natfrp/token.txt)"'
Restart=always
RestartSec=15
StartLimitIntervalSec=0
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now natfrp
```

### 5. 拿到公网地址

```bash
journalctl -u natfrp -n 30 --no-pager
```

输出里找：

```
UDP 隧道启动成功
使用 >>frp-end.com:38538<< 连接你的隧道
或使用节点 IP 连接 (不推荐) >>117.68.10.96:38538<<
```

**用域名，别用 IP** —— 节点 IP 会变，域名不会。

### 6. 验证

```bash
# RakNet Unconnected Ping
python3 tools/raknet-ping.py frp-end.com 38538
```

期望：

```
来自 117.68.10.96:38538
版本       26.33 (protocol 1001)
MOTD       "Retro-Mini-PC | NeoForge 21.1.250 | MC 1.21.1" / Another Geyser server.
玩家       0/10
```

能看到 MOTD 就说明整条链路通了。

---

## 端口/防火墙清单

ufw 规则（本项目实际配置）：

```bash
# 局域网
sudo ufw allow from 10.42.0.0/24 to any port 19132 proto udp comment "geyser bedrock"
sudo ufw allow from 10.42.0.0/24 to any port 19132 proto tcp comment "geyser signaling"
sudo ufw allow from 10.42.0.0/24 to any port 25565 proto tcp comment "minecraft java"
sudo ufw allow from 10.42.0.0/24 to any port 24454 proto udp comment "voice chat"

# 校园网段（如果服务端在校园网内）
sudo ufw allow from 10.8.0.0/16 to any port 19132 proto udp comment "geyser campus"
sudo ufw allow from 10.8.0.0/16 to any port 19132 proto tcp comment "geyser campus signal"
```

**注意**：走 SakuraFrp 时**不需要**开公网端口 —— frpc 是主动向外建立连接，隧道流量从 frp 节点转进来，ufw 看到的是本地回环流量。

`ss -lun` 显示监听 ≠ 防火墙放行。两个都要检查。

---

## 端口会变吗

**会。** 以下情况公网端口会变：

- 换了节点
- 删除重建隧道
- 节点维护后重新分配

所以**不要写死**。本项目在面板里做成了自动读取：

```python
def natfrp_address() -> str:
    """从 SakuraFrp 日志里读出公网地址，缓存 60 秒。"""
    out = subprocess.run(
        ["journalctl", "-u", "natfrp.service", "-n", "300", "--no-pager", "-o", "cat"],
        capture_output=True, text=True, timeout=8)
    hits = re.findall(r">>([A-Za-z0-9.\-]+:\d+)<<", out.stdout)
    # 日志里先给域名、后给节点 IP，优先取域名
    named = [h for h in hits if not re.fullmatch(r"\d+\.\d+\.\d+\.\d+:\d+", h)]
    return named[-1] if named else (hits[-1] if hits else "")
```

面板上直接显示 `frp-end.com:38538`，点一下复制。

---

## 延迟与限速

- 免费版节点限速 **10 Mibit/s**（约 1.25 MB/s）
- 实际游戏体验：延迟 **30–60ms**（国内节点），比海外方案好一个数量级
- MC 基岩版的流量很小（每秒几十 KB），10 Mibit/s 完全够用

节点提示「负载较高」不影响使用，只是高峰期可能略卡。想更好可以换节点。

---

## 备选：如果 SakuraFrp 不可用

1. **frp 自建** —— 需要一台有公网 IP 的服务器（阿里云/腾讯云学生机便宜）
2. **playit.gg** —— 海外，延迟高但不需要实名
3. **Tailscale + 朋友加入 tailnet** —— 适合小圈子
4. **ZeroTier** —— 类似 Tailscale

选哪个取决于：有没有公网服务器、能不能实名、朋友多不多。
