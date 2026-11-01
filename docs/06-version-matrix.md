# 六、版本矩阵与兼容性

## 为什么需要这张表

Geyser 的版本支持范围**不能靠猜，也不能靠扫 jar**。

### 错误做法：扫 jar 里的字符串

```bash
# 以为能这样判断
unzip -p Geyser.jar | strings | grep '1.26.0'
```

Geyser 2.12.1 的 jar 里**确实有** `1.26.0` 这个字符串（因为它的 codec 表里有这个版本的类），但它**实际不注册**这个协议版本。扫出来有 ≠ 能用。

### 正确做法：跑起来问它

```bash
# 用 fifo 喂命令，读启动输出
mkfifo /tmp/geyser_cmd
java -jar Geyser-Standalone.jar < /tmp/geyser_cmd &
echo "geyser version" > /tmp/geyser_cmd
# 输出里会有:
#   Java: 26.1 - 26.1.2
#   Bedrock: 26.0 - 26.33
```

**只有真跑起来读到的才算数。**

---

## Geyser 版本对照（实测）

| Geyser 版本 | 支持 Java | 支持基岩 | 备注 |
|---|---|---|---|
| **2.4.4-b705** | 1.21.1 | ≤ 1.21.44 | Modrinth 上 1.21.1 + neoforge 的**最后一版** |
| 2.9.6-b1133 | 1.21.11 | 1.21.130 – 26.20 | |
| **2.10.1-b1184** | **26.1 – 26.1.2** | **26.0 – 26.33** | ← **本项目用这版** |
| 2.11.3-b1254 | 26.2 | 26.30 – 26.52 | 不含 26.0 |
| 2.12.1-b1280 | 26.3 | 26.40 – 26.60 | `neoforge.mods.toml` 写死 `[26.3,26.4)` |

### 关键结论

手机基岩版是 **1.26.0.2**（协议 **26.0**）的话：

- 2.4.4 ❌ 最高只到 1.21.44
- 2.9.6 ❌ 最低要 1.21.130
- **2.10.1 ✅ 覆盖 26.0，且范围最宽（26.0–26.33）**
- 2.11.3 ❌ 最低 26.30
- 2.12.1 ❌ 最低 26.40

**只有 2.10.1 这一条路**，而且它要求 Java 侧是 26.1+ —— 你的服务端是 1.21.1，所以**必须**走 ViaProxy 做协议降级。

---

## Modrinth API 查询

```bash
# 查 1.21.1 + neoforge 的最新版
curl -s -H "User-Agent: your-name" \
  'https://api.modrinth.com/v2/project/wKkoqHrH/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D' \
  | python3 -c "import json,sys; v=json.load(sys.stdin); print(v[0]['version_number'])"
# 2.4.4-b705
```

**不带 `game_versions` 过滤**会返回 2.12.1-b1280（game_versions=26.3），在 1.21.1 上加载会直接崩：

```
Error during pre-loading phase: Mod geyser requires minecraft 26.3 or above
```

项目 ID：

| 项目 | Modrinth ID |
|---|---|
| Geyser | `wKkoqHrH` |
| Floodgate | `bWrNNfkb` |

---

## Geyser 官方下载 API

比 Modrinth 更直接，能拿到所有平台构建：

```bash
curl -s "https://download.geysermc.org/v2/projects/geyser/versions/2.10.1/builds" \
  | python3 -c "
import json,sys
b = json.load(sys.stdin)['builds'][-1]
print('build:', b['build'])
for plat, info in b['downloads'].items():
    print(f'  {plat}: {info[\"name\"]} sha256={info[\"sha256\"][:16]}...')
"
```

下载 URL 格式：

```
https://download.geysermc.org/v2/projects/geyser/versions/<ver>/builds/<build>/downloads/<platform>
```

platform 可选：`standalone`、`neoforge`、`fabric`、`viaproxy`、`spigot` 等。

---

## Floodgate 版本对照

Floodgate 必须和服务端的 **NeoForge 版本**匹配，不是 MC 版本。

| Floodgate | 要求 NeoForge | 本项目 |
|---|---|---|
| 2.2.4-b38 | 21.1.x | ✅ 用这个 |
| 2.2.7-b69 | 26.3.0.3+ | ❌ 会崩 |

装错版本的报错：

```
Error during pre-loading phase: Mod floodgate requires neoforge 26.3.0.3-beta or above
Mod floodgate requires minecraft 26.3 or above
Mod cloud requires minecraft 26.3 or above
net.neoforged.fml.ModLoadingException: Loading errors encountered
```

**注意**：Modrinth 上 Floodgate 的「最新版」是给 MC 26.x 的。查的时候必须带 `game_versions=["1.21.1"]` 过滤。

---

## ViaProxy / ViaVersion 版本

| 组件 | 版本 | 说明 |
|---|---|---|
| ViaProxy | 3.4.14 | 内置 ViaVersion 5.12.1-SNAPSHOT |
| ViaVersion | 5.12.1-SNAPSHOT | 支持 1.7.2 – 26.3 协议翻译 |

ViaProxy 日志会打印：

```
(ViaVersion) ViaVersion detected lowest supported version by the proxy: 1.7.2-1.7.5 (4)
(ViaVersion) Highest supported version by the proxy: 26.3 (777)
```

---

## 为什么「升级服务端到 26.3」不可行

有人会想：既然 Geyser 新版支持 26.3，那我把服务端升级到 26.3 不就行了？

**不行**，原因有三：

### 1. 低版本客户端连高版本服务端需要 ViaBackwards

ViaBackwards 做协议**向下**转换（让 1.20 客户端连 1.21 服务端）。它只存在于：

- Paper / Spigot 的插件版
- Velocity / BungeeCord 的代理版

**NeoForge 服务端没有官方实现。**

客户端侧倒是可以用 ViaFabricPlus，但那是给 Java 客户端的，基岩版用不了。

### 2. 协议通了也没用，模组墙还在

就算 ViaBackwards 能翻译协议，低版本客户端**装不了 26.3 的 NeoForge 模组**。NeoForge 的 mod 通道协商照样会拒绝它 —— 就是本文档讲的四道关卡。

升级只是把墙搬到更高的版本，墙本身没消失。

### 3. 成本巨大

- 37 个模组绝大多数没有 26.3 版本
- 存档内容会丢（方块 id、物品 id 全变了）
- 得重开服

### 4. 版本组合反而更差

假设服务端升到 26.3：

- Geyser 必须用 2.12.1（Java 26.3）
- 但 2.12.1 要求基岩 **26.40+**
- 手机是 **26.0**

**反而连不上了。**

---

## 兼容性检查清单

部署前逐项确认：

- [ ] `geyser version` 输出的 Bedrock 范围包含你手机的版本
- [ ] `geyser version` 输出的 Java 范围与 ViaProxy 的 target 匹配
- [ ] Floodgate 的 `neoforge.mods.toml` 里 `versionRange` 包含你的 NeoForge 版本
- [ ] NeoForge universal.jar 已打 4 个补丁（`javap` 验证 `checkPacket` 是 `0: return`）
- [ ] ViaProxy.jar 已打 2 个补丁（输出 `patched=true recipe=true decode=true`）
- [ ] `plugins/Geyser/key.pem` 与 `config/floodgate/key.pem` 内容一致
- [ ] `plugins/Geyser/config.yml` 里 `java.auth-type: floodgate`
- [ ] ufw 放行 19132 udp/tcp
- [ ] RakNet ping 能拿到 MOTD
