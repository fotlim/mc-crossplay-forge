# 三、ViaProxy 的两个崩溃点

ViaProxy 里跑的是 ViaVersion，负责 Java 协议版本翻译。装了一堆模组的服务端会让它遇到它不认识的包，默认行为是**抛异常断线**。

## 崩溃点 A：配方包空指针

### 症状

ViaProxy 日志：

```
java.lang.NullPointerException: Cannot invoke "String.startsWith(String)" because "identifier" is null
    at com.viaversion.viaversion.api.minecraft.item.data.Key.stripMinecraftNamespace(Key.java:...)
    at com.viaversion.viaversion.protocols.v1_21to1_21_2.rewriter.BlockItemPacketRewriter1_21_2
        .lambda$registerPackets$8(BlockItemPacketRewriter1_21_2.java:...)
```

玩家那边看到的是连接直接断开，没有提示。

### 源码

`com/viaversion/viaversion/protocols/v1_21to1_21_2/rewriter/BlockItemPacketRewriter1_21_2`

处理 `UPDATE_RECIPES` 包时，它要翻译配方序列化器的名字：

```java
String identifier = recipeSerializerMappings.identifier(serializerId);
// identifier 可能是 null —— 服务端装了带自定义配方类型的模组
Key.stripMinecraftNamespace(identifier);   // ← NPE
```

`recipeSerializerMappings` 是 ViaVersion 从原版数据里建的表。模组注册的自定义配方类型不在表里，查出来是 `null`。

### 补丁

紧跟 `astore 8`（存 identifier 的槽位）之后插入：

```java
if (identifier == null) { wrapper.cancel(); return; }
```

字节码层面：

```java
if (!done && opcode == Opcodes.ASTORE && var == 8) {
    done = true;
    Label ok = new Label();
    super.visitVarInsn(Opcodes.ALOAD, 8);
    super.visitJumpInsn(Opcodes.IFNONNULL, ok);
    super.visitVarInsn(Opcodes.ALOAD, 1);                       // wrapper
    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, WRAPPER, "cancel", "()V", true);
    super.visitInsn(Opcodes.RETURN);
    super.visitLabel(ok);
}
```

`wrapper.cancel()` 是 ViaVersion 自己的机制 —— 标记「这个包不要发」，但连接继续。

**语义**：这一包配方里有认不出来的配方，那整包不发。玩家少了几个配方，但能玩。

---

## 崩溃点 B：解码失败即断线

### 症状

```
java.lang.IllegalArgumentException: No data component serializer found for id 143
```

ViaProxy 日志里跟着 `BIO_read retries exceeded` 或 `Broken pipe`，连接没了。

### 源码

`com/viaversion/viaversion/platform/ViaCodecHandler.decode`

```java
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    ByteBuf copy = in.retain();
    user.get(ProtocolInfo.class).getCodecHandler()...;
    transformIncoming(...);      // ← 这里抛什么都直接外抛
    out.add(copy);
}
```

Netty 的 `decode` 抛异常 → pipeline 被标记为失败 → 连接断掉。ViaVersion 没有为「单个包翻译失败」做容错。

`id 143` 是 `CONTAINER_SET_CONTENT`（容器内容同步）。模组物品带了私有数据组件，ViaVersion 不认这个 id。

### 补丁

在「调 `transformIncoming`」和「把结果塞进 out」之间插 try/catch：

```java
try {
    user.transformIncoming(...);
} catch (Throwable t) {
    out.clear();
    return;      // 放弃这一个包，连接留着
}
```

### 字节码实现的坑

第一版试图在 `end` 标签处补一条 `NOP` 当异常区间的终点：

```java
target.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
```

结果异常表区间算成 `from == to`（因为 NOP 被写进了旧指令之间），JVM 报：

```
Illegal exception table range in class file
```

**正确做法**：把原 `invokeinterface` 换成「同一段代码 + 一个紧跟其后的 `GOTO`」，让 `safe` 标签成为原下一条指令的跳转目标。这样异常区间 `[start, end)` 覆盖 `{invoke, goto}` 两条指令，非空且合法：

```java
Label start = new Label(), end = new Label(),
      handler = new Label(), safe = new Label();
target.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
target.visitLabel(start);
target.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);   // 原指令
target.visitJumpInsn(Opcodes.GOTO, safe);                          // 新增
target.visitLabel(end);
target.visitLabel(handler);
target.visitVarInsn(Opcodes.ALOAD, 3);                             // out
target.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
target.visitInsn(Opcodes.RETURN);
target.visitLabel(safe);
```

实测异常表：`from=56 to=64 target=64`，合法。

### 另一个坑：别重发原指令

如果在 `visitMethodInsn` 里用 `super.visitMethodInsn` 把原指令再发一次（为了「先保护再执行」），会多压一个返回值到栈上：

```
NegativeArraySizeException: -3
```

要么不动原指令只插 `tryCatchBlock`，要么走上面的「换写法」。

---

## 为什么需要 COMPUTE_FRAMES

两个补丁都插了新的跳转目标（`ok` / `handler` / `safe`），必须重算栈帧：

```java
new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
```

而 `COMPUTE_FRAMES` 计算合并类型时需要解析类层次 —— 用默认的 `ClassWriter` 会去找 `getCommonSuperClass`，它默认用系统 classloader，找不到 ViaVersion 自己的类。

解决：挂一个指向**正在处理的 jar 自己**的 `URLClassLoader`：

```java
static class JarClassWriter extends ClassWriter {
    private final ClassLoader loader;
    @Override
    protected String getCommonSuperClass(String t1, String t2) {
        try {
            Class<?> c1 = Class.forName(t1.replace('/', '.'), false, loader);
            Class<?> c2 = Class.forName(t2.replace('/', '.'), false, loader);
            ...
        } catch (Throwable t) {
            return "java/lang/Object";     // 兜底，别让它崩
        }
    }
}
```

对比：NeoForge 的补丁只需要 `COMPUTE_MAXS`，因为它的补丁点都在方法入口，没有新的合并类型。

---

## 官方开关（更省事但更粗暴）

ViaProxy 其实有两个现成开关：

| 开关 | 默认 | 作用 |
|---|---|---|
| `--ignore-protocol-translation-errors` | false | 让 `channelRead` 吞掉翻译异常 |
| `--suppress-client-protocol-errors` | false | 抑制客户端协议错误 |

第一个看起来正好解决问题 B。但实测**不够**：

- 它的真值检查在 `ViaProxyViaCodec.channelRead` 头部，只覆盖那一条路径
- 配方 NPE 发生在 `decode` 里，不在 `channelRead` 的 try 块内

所以还是得打补丁。两个开关可以**同时开着**作为额外保险：

```yaml
# viaproxy.yml
ignore-protocol-translation-errors: true
suppress-client-protocol-errors: true
```

本项目实际配置就是这样 —— 补丁 + 开关双保险。
