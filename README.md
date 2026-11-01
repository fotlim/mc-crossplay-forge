# 让基岩版闯进 NeoForge 模组服

> 四道拦截、两个崩溃、一次字节码手术 —— 手机基岩版连进 37 模组的 NeoForge 1.21.1 服务端全记录

---

## 这个仓库解决什么问题

你想让**手机基岩版**（Bedrock）连进自己的 **NeoForge 模组服**。查遍教程，所有人都会告诉你「装 Geyser + Floodgate 就行」。

然后你装了，然后你撞墙了。

Geyser 官方从某个版本开始**只支持最新的 Java 版**。你的服务端是 1.21.1 + 37 个模组，Geyser-NeoForge 只能装到 2.4.4-b705，而这一版最高只认基岩 **1.21.44**。你手机上的版本是 **1.26.0.2**，连不上。

官方 wiki 给的答案是「用 ViaProxy + Geyser-ViaProxy」。你照做了，ViaProxy 成功把 1.26.0 的协议翻译成 1.21.1 —— **然后 NeoForge 一脚把你踢出来**：

```
You are trying to connect to a server that is running NeoForge, but you are not.
Please install NeoForge Version: 21.1.250 to connect to this server.
```

这句话是死路。你不是没装 NeoForge，你是**根本装不了** —— 你是个手机。

这个仓库记录了怎么把这条路走通，包括中间踩的每一个坑。

---

## 最终架构

```
┌─────────────┐
│  手机基岩版  │  1.26.0.2 (协议 26.0)
│  Bedrock    │
└──────┬──────┘
       │ RakNet UDP
       ▼
┌──────────────────────────────────────────────┐
│  SakuraFrp 内网穿透（UDP 隧道）                │
│  公网 ──► frp 节点 ──► 内网                    │
└──────┬───────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────┐
│  Geyser-ViaProxy 2.10.1-b1184                │
│  基岩协议 26.0 ──► Java 协议 26.1             │
└──────┬───────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────┐
│  ViaProxy 3.4.14（已打 2 个补丁）              │
│  Java 协议 26.1 ──► 1.21.1                   │
│  Floodgate 认证透传                           │
└──────┬───────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────┐
│  NeoForge 1.21.1 + 37 模组（已打 4 个补丁）    │
│  Java 玩家 / 基岩玩家 同服                    │
└──────────────────────────────────────────────┘
```

**每一层都在做「降级翻译」，每一层都可能断你。** 这个仓库的核心就是那 **6 个字节码补丁**。

---

## 三道手术

### 手术一：让 NeoForge 放行「不是 NeoForge」的客户端

NeoForge 服务端对非 NeoForge 客户端有**连续四道**拦截，每道都单独断连，报错还都不一样：

| # | 类 | 触发条件 | 玩家看到的 |
|---|---|---|---|
| 1 | `NetworkComponentNegotiator.negotiate` | 客户端通道为空 | `You are trying to connect to a server that is running NeoForge, but you are not` |
| 2 | `CheckExtensibleEnums.start` | 服务端有扩展枚举 | `does not support vanilla clients as it has extended enums` |
| 3 | `CheckFeatureFlags.start` | 服务端有自定义 FeatureFlag | `does not support vanilla clients as it has custom FeatureFlags` |
| 4 | `NetworkRegistry.checkPacket` | 模组发未协商的 payload | **`Invalid player data`**（最难查的一个） |

第 4 道最阴 —— 玩家已经拿到 entity id、已经被判定「进入世界」，然后在放进世界的那一瞬间被 payload 拦下，客户端只显示「无效的玩家数据」。日志里才有真话：

```
Payload carryon:sync_scripts may not be sent to the client!
```

补丁全部写在 [`tools/PatchNeoForge.java`](tools/PatchNeoForge.java)。

> **为什么不用 Mixin？** 试过了，不行。模组 Mixin 改不了 NeoForge 自己的类 —— 那些类由另一套 classloader 在模组加载前就加载完了。Mixin 配置会「成功加载」，但目标类一个字节都没变，静默失败。详见 [docs/04-asm-pitfalls.md](docs/04-asm-pitfalls.md#为什么不用-mixin)。

### 手术二：让 ViaProxy 别因为一个包就断线

ViaProxy 里 ViaVersion 做协议翻译时有两个致命点：

1. **配方包空指针** —— 处理 `UPDATE_RECIPES` 时，服务端装了带自定义配方类型的模组，`recipeSerializerMappings.identifier()` 返回 `null`，紧接着 `Key.stripMinecraftNamespace(null)` 抛 NPE，整条连接被掐。
2. **解码失败即断线** —— `ViaCodecHandler.decode` 把 `transformIncoming()` 的异常直接外抛。典型场景：模组物品带私有数据组件，`No data component serializer found for id 143`。

补丁见 [`tools/PatchViaProxy.java`](tools/PatchViaProxy.java)：前者「这一包配方不发，连接继续」，后者「放弃这一个包，连接留着」。

### 手术三：把公网隧道架起来

校园网 / 家用宽带都是共享 NAT，没有端口映射。Cloudflare Tunnel 只吃 HTTP，MC 是裸 UDP，用不了。最后选了 **SakuraFrp**（国内节点，延迟低，免费版够用）。

详见 [docs/05-network.md](docs/05-network.md)。

---

## 快速开始

### 环境

- 服务端：NeoForge 1.21.1（本例 21.1.250），Java 21
- 需要的 jar：`neoforge-<ver>-universal.jar`、`ViaProxy.jar`
- 工具：JDK 21、`asm-9.10.1.jar`

### 1. 打 NeoForge 补丁

```bash
# 备份原件！补丁是「从原件重新生成」，不是原地改
cp neoforge-21.1.250-universal.jar neoforge-universal.jar.orig

javac -cp asm-9.10.1.jar -d . PatchNeoForge.java VerifyPatch.java
java  -cp "asm-9.10.1.jar:." PatchNeoForge \
      neoforge-universal.jar.orig neoforge-patched.jar
# 期望输出: patch negotiator=true extensibleEnums=true featureFlags=true checkPacket=true
```

自检（**注意 cp 末尾那个 `.`**，少了会报「找不到主类」）：

```bash
CP="neoforge-patched.jar:$(find libraries -name '*.jar' | tr '\n' ':')."
java -Xverify:all -cp "$CP" VerifyPatch
# 期望输出: result = NegotiationResult[components=[], success=true, failureReasons={}]
#           SELF-TEST PASSED
```

> `CheckExtensibleEnums` / `CheckFeatureFlags` 的静态初始化依赖 FML 运行时（`ModList`），在裸 JVM 里会抛 `ExceptionInInitializerError` —— **这是预期的**。校验器在初始化之前就跑完了，只要不是 `VerifyError` 就算过。

装回去：

```bash
cp neoforge-patched.jar \
   mcserver/libraries/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-universal.jar
```

### 2. 打 ViaProxy 补丁

```bash
javac -cp asm-9.10.1.jar -d . PatchViaProxy.java
java  -cp "asm-9.10.1.jar:." PatchViaProxy ViaProxy.jar.orig ViaProxy-patched.jar
# 期望输出: patched=true recipe=true decode=true
```

### 3. 架 Geyser-ViaProxy

把 `Geyser-ViaProxy.jar` 放进 `plugins/`，配置 `plugins/Geyser/config.yml`：

```yaml
bedrock:
  port: 19132
java:
  auth-type: floodgate        # 默认是 online，必须改
motd:
  passthrough-motd: true
```

从服务端的 `config/floodgate/key.pem` 复制一份到 `plugins/Geyser/key.pem`。

### 4. 启动

```bash
java -Xmx512M -jar ViaProxy.jar cli \
     --bind-address 127.0.0.1:25568 \
     --target-address 127.0.0.1:25565
```

systemd unit 见 [`systemd/`](systemd/)。

---

## 版本矩阵（实测，不是抄的）

Geyser 的版本支持范围**不能看 jar 里有没有那个字符串** —— 会误判。必须真跑起来用 `geyser version` 命令读。

| Geyser | 支持 Java | 支持基岩 | 备注 |
|---|---|---|---|
| 2.4.4-b705 | 1.21.1 | ≤ 1.21.44 | Modrinth 上 1.21.1+neoforge 的最后一版 |
| 2.9.6-b1133 | 1.21.11 | 1.21.130 – 26.20 | |
| **2.10.1-b1184** | 26.1 – 26.1.2 | **26.0 – 26.33** | ← **覆盖手机 26.0 且范围最宽** |
| 2.11.3-b1254 | 26.2 | 26.30 – 26.52 | 不含 26.0 |
| 2.12.1-b1280 | 26.3 | 26.40 – 26.60 | `neoforge.mods.toml` 写死 `[26.3,26.4)`，1.21.1 上加载失败 |

**结论**：手机是 26.0 的话，只有 **2.10.1** 这一条路，且必须走 ViaProxy。

---

## 踩坑速查

完整清单见 [`docs/errors/`](docs/errors/)，这里放最要命的几条：

| 报错 | 真实原因 | 在哪解决 |
|---|---|---|
| `You are trying to connect to a server that is running NeoForge, but you are not` | 通道协商失败 | 补丁 1 |
| `does not support vanilla clients as it has extended enums` | 扩展枚举守卫 | 补丁 2 |
| `does not support vanilla clients as it has custom FeatureFlags` | FeatureFlag 守卫 | 补丁 3 |
| **`Invalid player data`** | 模组 payload 被拦（日志里才有真话） | 补丁 4 |
| `Key.stripMinecraftNamespace` NPE | 自定义配方序列化器 | ViaProxy 补丁 A |
| `No data component serializer found for id 143` | 模组物品私有组件 | ViaProxy 补丁 B |
| `This server does not support vanilla clients` | 同 2/3 | 同上 |

---

## 已知限制

- **语音模组用不了**：Simple Voice Chat 是纯 Java 模组，Geyser 不转发音频。基岩玩家听不到也说不了。
- **模组内容大量丢失**：基岩版看不到自定义方块、物品、GUI。原版客户端对未知 payload 一律静默忽略 —— 这也是补丁 4 能生效的原因。
- **补丁是版本绑定的**：目标类名、方法描述符、局部变量槽位都写死在补丁程序里。换 NeoForge 或 ViaProxy 版本必须重新用 `javap` 核对，不能盲跑。
- **`checkPacket` 改成空操作是全局的**：它同时放行了对 Java 玩家的检查。正常 NeoForge 客户端本来就走协商过的通道，实际影响很小，但你要知道这件事。
- **协议版本漂移**：手机客户端一升级，Geyser 的支持范围就可能不覆盖了。这时候要么等 Geyser 更新，要么手机降版本。

---

## 目录

```
.
├── README.md                      你在这里
├── LICENSE.md                     MIT + 第三方组件许可说明
├── tools/
│   ├── PatchNeoForge.java         NeoForge 四道关卡补丁
│   ├── PatchViaProxy.java         ViaProxy 两个崩溃点补丁
│   ├── VerifyPatch.java           补丁自检
│   ├── build.sh                   一键编译 + 打补丁
│   └── raknet-ping.py             不开游戏验证基岩服通不通
├── systemd/
│   ├── viaproxy.service
│   └── natfrp.service
└── docs/
    ├── 01-architecture.md         链路逐层拆解
    ├── 02-neoforge-patches.md     四道关卡源码级分析
    ├── 03-viaproxy-patches.md     两个崩溃点分析
    ├── 04-asm-pitfalls.md         ASM 字节码踩坑（最有价值的一篇）
    ├── 05-network.md              内网穿透选型与配置
    ├── 06-version-matrix.md       版本对照表
    ├── 07-config-samples.md       配置样例与验证清单
    └── errors/
        └── README.md              报错速查手册
```

---

## 免责声明

这些补丁是**改字节码**，绕过的是上游有意设置的安全/兼容检查。它们只适合**自己玩、自己负责**的私人服务器。别拿去做公开服，也别指望上游会支持这种用法 —— 出问题自己 debug。

如果哪天 NeoForge 或 Geyser 官方提供了原生支持，请优先用官方的。

## License

MIT
