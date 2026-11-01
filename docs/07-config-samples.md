# Geyser + ViaProxy 配置样例

## plugins/Geyser/config.yml

只列关键项，完整文件由 Geyser 首次启动生成。

```yaml
bedrock:
  # 监听地址。0.0.0.0 表示所有网卡
  address: 0.0.0.0
  port: 19132
  # 不要 clone 远端端口，我们固定用 19132
  clone-remote-port: false

java:
  # 【必须改】默认是 online，基岩玩家会被当正版校验
  # floodgate 表示信任 Floodgate 加密的身份
  auth-type: floodgate

motd:
  # 透传 Java 服的真实 MOTD，手机上能看到模组信息
  passthrough-motd: true
  max-players: 100

gameplay:
  server-name: Geyser
```

### 为什么 `auth-type` 必须是 `floodgate`

| 取值 | 行为 |
|---|---|
| `online`（默认） | 向 Mojang 验证玩家身份 → 基岩玩家没有 Java 账号 → 全部失败 |
| `offline` | 不验证，玩家名直接进 → 谁都能冒充 |
| **`floodgate`** | 用 Floodgate 密钥加密基岩身份，服务端 Floodgate 模组解出来 |

`floodgate` 模式下，基岩玩家在服务端的用户名带 `.` 前缀（如 `.fotlim`），这是 Floodgate 的标记 —— 表示「这是基岩玩家，不是 Java 正版」。

## key.pem 必须两边一致

```bash
# Floodgate 模组生成的位置
mcserver/config/floodgate/key.pem

# Geyser 读取的位置
viaproxy/plugins/Geyser/key.pem

# 复制
cp mcserver/config/floodgate/key.pem viaproxy/plugins/Geyser/key.pem
```

不一致的后果：Geyser 用 A 密钥加密身份，Floodgate 用 B 密钥解密 → 解不开 → 玩家被当成未认证，可能直接断连或进不了世界。

## viaproxy.yml

```yaml
bind-address: 127.0.0.1:25568      # 只听本地，外面走 Geyser 的 19132
target-address: 127.0.0.1:25565    # 指向 NeoForge
target-version: Auto Detect (1.7+ servers)
connect-timeout: 8000
proxy-online-mode: false
auth-method: NONE                  # 认证由 Floodgate 管，代理不做
compression-threshold: 256

# 这两个开关是额外保险，补丁才是主力
ignore-protocol-translation-errors: true
suppress-client-protocol-errors: true

# 基岩版用不上，Java 玩家经代理连语音服时才需要
simple-voice-chat-support: false
```

### 为什么 `bind-address` 是 `127.0.0.1`

ViaProxy 是**内部组件**，只有 Geyser 需要连它。对外暴露的只有 Geyser 的 19132。

如果把 ViaProxy 也暴露到 0.0.0.0，会多一个攻击面，而且玩家可能绕过 Geyser 直连（那样就失去了基岩身份）。

## 启动顺序

```
1. NeoForge (25565)          ← 最先，其他都依赖它
2. ViaProxy (25568)          ← 连 25565
3. Geyser (19132, 内嵌)      ← 连 25568
4. frpc (隧道)               ← 转发到 19132
```

systemd 的 `After=` 已经表达了依赖，但 `Type=simple` 不会等端口真的起来。ViaProxy 有 `Restart=always` + `RestartSec=15`，起早了会自己重试。

## 验证配置

```bash
# 1. NeoForge 在听吗
ss -lntp | grep 25565

# 2. ViaProxy 在听吗（应该只有 127.0.0.1）
ss -lntp | grep 25568

# 3. Geyser 在听吗
ss -lunp | grep 19132

# 4. RakNet ping 通吗
python3 tools/raknet-ping.py 127.0.0.1 19132

# 5. 走隧道通吗
python3 tools/raknet-ping.py frp-end.com 38538

# 6. Floodgate 密钥一致吗
md5sum mcserver/config/floodgate/key.pem viaproxy/plugins/Geyser/key.pem
```
