# 二、NeoForge 的四道关卡

NeoForge 对「不是 NeoForge 客户端」的连接有连续四道拦截。每道都独立断连，报错各不相同 —— 这意味着你**修好一道才会看见下一道**，很容易以为是同一个问题反复出现。

## 关卡 1：网络通道协商

### 症状

```
You are trying to connect to a server that is running NeoForge, but you are not.
Please install NeoForge Version: 21.1.250 to connect to this server.
```

客户端和 ViaProxy 日志都是这句。

### 源码

`net/neoforged/neoforge/network/negotiation/NetworkComponentNegotiator.java`

```java
public static NegotiationResult negotiate(List<NetworkComponent> server, List<NetworkComponent> client) {
    // 服务端声明了自己要的通道，客户端一个都没有 → 判失败
    ...
}
```

NeoForge 在登录阶段交换「网络组件」清单。服务端列出自己需要的通道（模组注册的），客户端回自己的。基岩桥接的客户端**根本不发这个包**，所以 `client` 是空列表。

服务端看到「我要 5 个通道，你 0 个」→ 协商失败 → 踢人。

### 补丁

```java
if (client.isEmpty()) return new NegotiationResult(List.of(), true, Map.of());
```

放在 `negotiate` 方法**入口**。空客户端直接判成功，返回空的组件列表。

### 为什么这样安全

正常的 NeoForge 客户端 `client` 永远非空（至少发自己的 NeoForge 通道清单）。所以这个早退**只对原版/桥接客户端生效**，Java 玩家之间的流程完全不变。

---

## 关卡 2：扩展枚举守卫

### 症状

```
This server does not support vanilla clients as it has extended enums used in clientbound networking
```

### 源码

`net/neoforged/neoforge/network/configuration/CheckExtensibleEnums.java`

```java
public class CheckExtensibleEnums implements ConfigurationTask {
    public static final Type TYPE = new Type("neo:check_extensible_enums");
    private final ServerConfigurationPacketListener listener;

    public void start(Consumer<Packet<?>> consumer) {
        // 如果服务端有模组往「枚举」里加了东西，且这些枚举会发给客户端
        // → 原版客户端解析不了 → 直接断
        if (hasExtendedEnums()) {
            listener.disconnect(...);
            return;
        }
        listener.finishCurrentTask(TYPE);
    }
}
```

模组可以扩展原版的枚举（比如新的 `BlockEntityType`、新的 `EntityType`）。这些扩展值在 clientbound 包里会以「原版客户端不认识的 id」出现，原版客户端收到会崩，所以 NeoForge 主动拦住。

### 补丁

```java
if (listener.getConnectionType().isOther()) {
    listener.finishCurrentTask(TYPE);
    return;
}
```

`ConnectionType.isOther()` 就是「这个连接不是 NeoForge 客户端」—— 正好是要放行的那些。

### 关键教训：不能把原断连分支改成死代码

第一版尝试把 `if (hasExtendedEnums())` 的判断改成恒 false，结果：

```
VerifyError: Expecting a stack map frame at branch target 84
```

因为原断连分支变成不可达代码，而 JVM 校验器**要求不可达块也有 stackmap frame**。

正确做法是**在方法入口插早退** —— 让原方法体仍然可达，只给新加的跳转目标补一个 `F_SAME` frame：

```java
target.visitLabel(cont);
target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
```

---

## 关卡 3：FeatureFlag 守卫

### 症状

```
This server does not support vanilla clients as it has custom FeatureFlags
```

### 源码

`net/neoforged/neoforge/network/configuration/CheckFeatureFlags.java`

结构和关卡 2 一模一样，只是检查的东西不同：模组可以通过 NeoForge 注册自定义的 `FeatureFlag`（数据包驱动的内容开关）。原版客户端不认识这些 flag，收到就崩。

### 补丁

和关卡 2 完全相同 —— 同一个 `patchVanillaGuard` 方法处理两个类。

---

## 关卡 4：未协商 payload（最阴的一个）

### 症状

客户端只显示：

```
无效的玩家数据
```
或英文 `Invalid player data`

**日志里才有真话**（在服务端日志，不在 ViaProxy）：

```
ERROR [net.neoforged.bus.EventBus]: Exception caught during firing event:
  Payload carryon:sync_scripts may not be sent to the client!
java.lang.UnsupportedOperationException: Payload carryon:sync_scripts may not be sent to the client!
Couldn't place player in world
Floodgate 玩家 .fotlim 已断开连接
.fotlim lost connection: Invalid player data
```

### 为什么最难查

看日志顺序：

```
Floodgate player who is logged in as .fotlim 00000000-...-01f9af656f8a joined
.fotlim[/10.42.0.44:39004] logged in with entity id 28 at (7.5, 114.0, -9.5)
ERROR ... Payload carryon:sync_scripts may not be sent to the client!
Couldn't place player in world
```

玩家**已经拿到 entity id 28**，**已经算出坐标 (7.5, 114.0, -9.5)**，日志里 "joined" 都打出来了。然后在「放进世界」的最后一步被拦下。

前面的认证、协议转换、协商、配置阶段**全部通过**。这会让你以为问题在别处。

### 源码

`net/neoforged/neoforge/network/registration/NetworkRegistry.java`

```java
public static void checkPacket(Packet<?> packet, ServerCommonPacketListener listener) {
    // 这个 payload 的通道有没有协商过？
    if (!negotiatedChannels.contains(packet.type().id())) {
        throw new UnsupportedOperationException(
            "Payload " + id + " may not be sent to the client!");
    }
}
```

有两个重载（分别针对 server 和 client 方向），都要处理。

### 补丁

**整个方法体替换为 `RETURN`**：

```java
return new MethodVisitor(Opcodes.ASM9) {   // 注意：不带 delegate
    @Override public void visitCode()  { target.visitCode(); target.visitInsn(Opcodes.RETURN); }
    @Override public void visitMaxs(int a, int b) { target.visitMaxs(0, 0); }
    @Override public void visitEnd()   { target.visitEnd(); }
};
```

用 `new MethodVisitor(Opcodes.ASM9)`（**不传 target 作为 delegate**）把所有原始指令回调吞掉，只在 `visitCode` 时输出一条 `RETURN`。

### 为什么这样能work

模组的私有 payload 照常发给原版/基岩客户端。**原版客户端对未知 payload 一律静默忽略** —— 这是 MC 网络协议的容错设计。所以这些包发出去不会崩，只是没效果。

代价：基岩玩家看不到那些模组内容（本来也看不到）。但连接能活。

### 副作用警告

`checkPacket` 是**全局**的，改成空操作后对 Java 玩家也不检查了。正常 NeoForge 客户端本来就只发协商过的通道，实际影响很小。但这是「为了自己玩」的取舍，公开服别这么干。

---

## 四道关卡的执行顺序

```
登录阶段
  └─ 关卡 1（negotiate）
       │  过了 → 进 CONFIGURATION
       ▼
配置阶段
  ├─ 关卡 2（CheckExtensibleEnums）
  ├─ 关卡 3（CheckFeatureFlags）
       │  都过了 → 进 PLAY
       ▼
进世界瞬间
  └─ 关卡 4（checkPacket）
       │  过了 → 玩家能动
       ▼
```

所以你修好 1 之后才会看到 2 的报错，修好 2 才看到 3。**四道必须全修**。

## 验证补丁生效

```bash
# 看 checkPacket 是不是真的变成 return
javap -c -p -cp neoforge-patched.jar \
  net.neoforged.neoforge.network.registration.NetworkRegistry \
  | grep -A2 'checkPacket'
# 应看到: 0: return
```

```bash
# 跑自检
java -Xverify:all -cp "$CP" VerifyPatch
# result = NegotiationResult[components=[], success=true, failureReasons={}]
# SELF-TEST PASSED
```
