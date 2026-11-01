# 报错速查手册

按报错原文查。左边是你**看到的**，右边是**真实原因**和在哪解决。

---

## NeoForge 侧

### `You are trying to connect to a server that is running NeoForge, but you are not.`

```
You are trying to connect to a server that is running NeoForge, but you are not.
Please install NeoForge Version: 21.1.250 to connect to this server.
```

| | |
|---|---|
| **真实原因** | `NetworkComponentNegotiator.negotiate` 判定通道协商失败（客户端通道列表为空） |
| **不是** | 真的缺 NeoForge 客户端 —— 你装不了，你是基岩版 |
| **解决** | 补丁 1：`if (client.isEmpty()) return new NegotiationResult(List.of(), true, Map.of());` |
| **文档** | [02-neoforge-patches.md#关卡-1](../02-neoforge-patches.md#关卡-1网络通道协商) |

---

### `does not support vanilla clients as it has extended enums`

```
This server does not support vanilla clients as it has extended enums used in clientbound networking
```

| | |
|---|---|
| **真实原因** | `CheckExtensibleEnums.start` 守卫：服务端有模组扩展了会发给客户端的枚举 |
| **解决** | 补丁 2：连接类型 `isOther()` 时直接 `finishCurrentTask(TYPE)` 返回 |
| **文档** | [02-neoforge-patches.md#关卡-2](../02-neoforge-patches.md#关卡-2扩展枚举守卫) |

---

### `does not support vanilla clients as it has custom FeatureFlags`

```
This server does not support vanilla clients as it has custom FeatureFlags
```

| | |
|---|---|
| **真实原因** | `CheckFeatureFlags.start` 守卫：服务端有模组注册了自定义 FeatureFlag |
| **解决** | 补丁 3：同关卡 2，同一个 `patchVanillaGuard` 处理 |
| **文档** | [02-neoforge-patches.md#关卡-3](../02-neoforge-patches.md#关卡-3featureflag-守卫) |

---

### `Invalid player data` / `无效的玩家数据` ← **最难查**

客户端只显示这一句，没有任何细节。

**服务端日志里才有真话**（注意：是服务端，不是 ViaProxy）：

```
Floodgate player who is logged in as .fotlim 00000000-0000-0000-0009-01f9af656f8a joined
.fotlim[/10.42.0.44:39004] logged in with entity id 28 at (7.5, 114.0, -9.5)
ERROR [net.neoforged.bus.EventBus]: Exception caught during firing event:
  Payload carryon:sync_scripts may not be sent to the client!
java.lang.UnsupportedOperationException: Payload carryon:sync_scripts may not be sent to the client!
Couldn't place player in world
Floodgate 玩家 .fotlim 已断开连接
.fotlim lost connection: Invalid player data
```

| | |
|---|---|
| **真实原因** | `NetworkRegistry.checkPacket` 拦截模组发出的未协商 payload |
| **迷惑点** | 玩家已拿到 entity id、已算出坐标、日志「joined」都打出来了，前面的关卡全过了 |
| **解决** | 补丁 4：`checkPacket` 两个重载整个方法体替换为 `RETURN` |
| **文档** | [02-neoforge-patches.md#关卡-4](../02-neoforge-patches.md#关卡-4未协商-payload最阴的一个) |

---

## ViaProxy / ViaVersion 侧

### `Cannot invoke "String.startsWith(String)" because "identifier" is null`

```
java.lang.NullPointerException: Cannot invoke "String.startsWith(String)" because "identifier" is null
    at ...Key.stripMinecraftNamespace(Key.java:...)
    at ...BlockItemPacketRewriter1_21_2.lambda$registerPackets$8(...)
```

| | |
|---|---|
| **真实原因** | 处理 `UPDATE_RECIPES` 时，模组的自定义配方类型不在 ViaVersion 的映射表里，返回 null |
| **解决** | ViaProxy 补丁 A：`if (identifier == null) { wrapper.cancel(); return; }` |
| **文档** | [03-viaproxy-patches.md#崩溃点-a](../03-viaproxy-patches.md#崩溃点-a配方包空指针) |

---

### `No data component serializer found for id 143`

```
java.lang.IllegalArgumentException: No data component serializer found for id 143
```

跟着 `BIO_read retries exceeded` / `Broken pipe`。

| | |
|---|---|
| **真实原因** | `CONTAINER_SET_CONTENT` 包里模组物品带私有数据组件，ViaVersion 不认这个 id |
| **解决** | ViaProxy 补丁 B：在 `decode` 里插 `catch (Throwable t) { out.clear(); return; }` |
| **文档** | [03-viaproxy-patches.md#崩溃点-b](../03-viaproxy-patches.md#崩溃点-b解码失败即断线) |

---

### `Mod geyser requires minecraft 26.3 or above`

```
Error during pre-loading phase: Mod geyser requires minecraft 26.3 or above
net.neoforged.fml.ModLoadingException: Loading errors encountered
```

| | |
|---|---|
| **真实原因** | 从 Modrinth 下了「最新版」Geyser，那是给 MC 26.3 的 |
| **解决** | 查询时必须带 `game_versions=["1.21.1"]` 过滤，或改用 Geyser-ViaProxy |
| **文档** | [06-version-matrix.md](../06-version-matrix.md#modrinth-api-查询) |

---

### `Mod floodgate requires neoforge 26.3.0.3-beta or above`

```
Error during pre-loading phase: Mod floodgate requires neoforge 26.3.0.3-beta or above
Mod floodgate requires minecraft 26.3 or above
Mod cloud requires minecraft 26.3 or above
```

| | |
|---|---|
| **真实原因** | Floodgate 2.2.7-b69 是给 MC 26.x 的，服务端是 1.21.1 |
| **解决** | 换 **Floodgate-Neoforge-2.2.4-b38**（或对应 21.1.x 的版本） |
| **文档** | [06-version-matrix.md#floodgate-版本对照](../06-version-matrix.md#floodgate-版本对照) |

---

### `Geyser 代理已过时，仅支持 1.21.44 及以下`

手机端弹窗。

| | |
|---|---|
| **真实原因** | 服务端装的是 Geyser 2.4.4-b705，最高只认基岩 1.21.44，手机是 1.26.0.2 |
| **解决** | 改用 ViaProxy + Geyser-ViaProxy 2.10.1-b1184 |
| **文档** | [01-architecture.md](../01-architecture.md#为什么不直接用-geyser-neoforge-插件) |

---

## ASM / 字节码侧

### `Unsupported class file major version 65`

```
java.lang.IllegalArgumentException: Unsupported class file major version 65
```

| | |
|---|---|
| **真实原因** | ASM 9.3 只认到 Java 19，class 文件是 Java 21（major 65） |
| **解决** | 换 **ASM 9.10.1** 或更新 |
| **文档** | [04-asm-pitfalls.md#坑-6](../04-asm-pitfalls.md#坑-6asm-版本要跟得上-jdk) |

---

### `Cannot invoke "MethodVisitor.visitCode()" because "this.mv" is null`

```
java.lang.NullPointerException: Cannot invoke
  "org.objectweb.asm.MethodVisitor.visitCode()" because "this.mv" is null
```

| | |
|---|---|
| **真实原因** | 匿名内部类里局部变量叫 `mv`，被 `MethodVisitor` 继承的 protected 字段 `mv` 遮蔽 |
| **解决** | 局部变量改名 `target` |
| **文档** | [04-asm-pitfalls.md#坑-1](../04-asm-pitfalls.md#坑-1methodvisitormv-字段遮蔽--最隐蔽的一个) |

---

### `must be InterfaceMethodref constant`

```
java.lang.IncompatibleClassChangeError:
  Method 'java.util.List java.util.List.of()' must be InterfaceMethodref constant
```

| | |
|---|---|
| **真实原因** | `visitMethodInsn(INVOKESTATIC, ..., isInterface)` 最后一个参数传了 `false` |
| **解决** | `List.of` / `Map.of` / 接口方法一律 `true` |
| **文档** | [04-asm-pitfalls.md#坑-2](../04-asm-pitfalls.md#坑-2接口静态方法的-isinterface-标志) |

---

### `Expecting a stack map frame`

```
VerifyError: Expecting a stack map frame at branch target 24
VerifyError: Expecting a stack map frame ... @84
```

| | |
|---|---|
| **真实原因** | 新插入的跳转目标没补 frame，或原分支被改成不可达代码 |
| **解决** | ① 新 label 后补 `visitFrame(Opcodes.F_SAME, 0, null, 0, null)`；② **改控制流为方法入口早退**，别让原代码不可达 |
| **文档** | [04-asm-pitfalls.md#坑-3](../04-asm-pitfalls.md#坑-3不可达代码也需要-stackmap-frame) |

---

### `Illegal exception table range`

```
java.lang.ClassFormatError: Illegal exception table range in class file
```

| | |
|---|---|
| **真实原因** | `visitTryCatchBlock(start, end, ...)` 的 `start == end`，保护区间长度为 0 |
| **解决** | 把原指令换写成「原指令 + GOTO」，让区间覆盖两条指令 |
| **文档** | [04-asm-pitfalls.md#坑-4](../04-asm-pitfalls.md#坑-4异常表区间不能为空) |

---

### `NegativeArraySizeException: -3`

```
java.lang.NegativeArraySizeException: -3
```

| | |
|---|---|
| **真实原因** | `visitMethodInsn` 里 `super` 发一次、`target` 又发一次，多压了一个返回值到栈上 |
| **解决** | 只发一次：要么不动原指令只插 `tryCatchBlock`，要么完整重写那一段 |
| **文档** | [04-asm-pitfalls.md#坑-5](../04-asm-pitfalls.md#坑-5重发指令会多压栈) |

---

### `找不到或无法加载主类 VerifyPatch`

```
错误: 找不到或无法加载主类 VerifyPatch
```

| | |
|---|---|
| **真实原因** | classpath 末尾少了 `.`（当前目录） |
| **解决** | `-cp "$CP:."` 或 `CP="...:."` |
| **文档** | [04-asm-pitfalls.md#坑-7](../04-asm-pitfalls.md#坑-7verifypatch-的-classpath-末尾必须有-) |

---

### `Unable to locate obfuscation mapping for @Inject target`

```
Unable to locate obfuscation mapping for @Inject target negotiate
```

| | |
|---|---|
| **真实原因** | Mixin 注解处理器在编译期要 obfuscation mapping |
| **解决** | `javac -proc:none` |
| **文档** | [04-asm-pitfalls.md#编译时还有个坑](../04-asm-pitfalls.md#编译时还有个坑) |

---

## 网络侧

### 手机连不上，日志里一条连接记录都没有

| | |
|---|---|
| **真实原因** | 手机走**蜂窝流量**却填了内网 IP（如 `10.42.0.1`），包根本到不了 |
| **怎么确认** | `journalctl -u viaproxy` 里完全没有 `玩家已连接` 记录 |
| **解决** | 用公网隧道地址（SakuraFrp 的 `xxx.com:port`） |
| **文档** | [05-network.md](../05-network.md) |

---

### `curl https://api.ipify.org` 超时 / 从内网打自己公网 IP 不通

| | |
|---|---|
| **真实原因** | 共享 NAT，运营商不给端口映射 |
| **解决** | 内网穿透 |
| **文档** | [05-network.md](../05-network.md#问题) |

---

### Cloudflare Tunnel 配 `tcp://` 报错

| | |
|---|---|
| **真实原因** | Tunnel 只吃 HTTP/HTTPS，MC 是裸 TCP/UDP |
| **解决** | 换 SakuraFrp / playit.gg / 自建 frp；Spectrum 是企业套餐 |
| **文档** | [05-network.md#为什么-cloudflare-tunnel-不行](../05-network.md#为什么-cloudflare-tunnel-不行) |

---

## 排查顺序建议

遇到问题按这个顺序查，能省很多时间：

1. **先看服务端日志** `mcserver/logs/latest.log` —— 客户端的报错往往是二手信息
2. **再看 ViaProxy 日志** `journalctl -u viaproxy` —— 协议翻译层的问题在这里
3. **再看 Geyser 日志** `viaproxy/logs/latest.log` —— 基岩侧的问题在这里
4. **最后看隧道** `journalctl -u natfrp` —— 网络层的问题在这里

哪一层没有日志，问题就在那一层的**上游**。
