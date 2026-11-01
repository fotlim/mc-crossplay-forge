# 四、ASM 字节码踩坑实录

这篇是整个仓库里**最值钱**的部分。前面几篇讲「为什么」，这篇讲「怎么才能真的改对」。

改字节码的难点不在于理解目标逻辑，而在于 JVM 校验器会因为你没料到的一个细节整类拒绝加载，而报错信息往往指向别处。

下面每一条都是实际撞过、实际修好的。

---

## 坑 1：`MethodVisitor.mv` 字段遮蔽 —— 最隐蔽的一个

### 现象

```
java.lang.NullPointerException: Cannot invoke
  "org.objectweb.asm.MethodVisitor.visitCode()" because "this.mv" is null
```

你明明 `new MethodVisitor(Opcodes.ASM9, target)` 传了 delegate，`mv` 怎么会是 null？

### 原因

`MethodVisitor` 自己有个**继承来的 protected 字段**：

```java
public abstract class MethodVisitor {
    protected MethodVisitor mv;      // ← 就是它
    protected MethodVisitor(final int api, final MethodVisitor methodVisitor) {
        this.mv = methodVisitor;
    }
}
```

如果你在匿名内部类里写：

```java
public MethodVisitor visitMethod(...) {
    final MethodVisitor mv = super.visitMethod(...);     // ← 灾难
    return new MethodVisitor(Opcodes.ASM9) {             // 注意：没传 delegate
        @Override public void visitCode() {
            mv.visitCode();        // ← 解析成 this.mv（字段），不是外层局部变量！
        }
    };
}
```

Java 的作用域规则：**匿名类内部，字段优先于外层局部变量**。所以 `mv` 指向的是 `this.mv`（因为没传 delegate，所以是 `null`），不是你以为的那个局部变量。

### 修法

局部变量改名：

```java
final MethodVisitor target = super.visitMethod(...);     // 别叫 mv
```

就这一条，坑了两次（NeoForge 补丁和 ViaProxy 补丁各一次）。

---

## 坑 2：接口静态方法的 `isInterface` 标志

### 现象

```
java.lang.IncompatibleClassChangeError:
  Method 'java.util.List java.util.List.of()' must be InterfaceMethodref constant
```

### 原因

`List.of()` / `Map.of()` 是 **Java 8+ 的接口静态方法**。在字节码里，调用它们的常量池引用类型必须是 `InterfaceMethodref`，不是 `Methodref`。

ASM 的 `visitMethodInsn` 最后一个参数 `isInterface` 就是控制这个的：

```java
visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
//                                                                          ↑ 必须是 true
```

写成 `false` 编译能过、字节码能生成，但 JVM 加载时报 `IncompatibleClassChangeError`。

### 规律

| 调用目标 | isInterface |
|---|---|
| `java/util/List.of` | **true** |
| `java/util/Map.of` | **true** |
| `java/util/List.clear`（INVOKEINTERFACE） | true |
| `PacketWrapper.cancel`（INVOKEINTERFACE） | true |
| `ConnectionType.isOther`（普通类方法） | false |
| `NegotiationResult.<init>`（INVOKESPECIAL） | false |

判据：**目标是不是 interface**。`List`/`Map`/`PacketWrapper` 是接口 → true；`ConnectionType`/`NegotiationResult` 是类 → false。

---

## 坑 3：不可达代码也需要 stackmap frame

### 现象

```
VerifyError: Expecting a stack map frame at branch target 24
```
或
```
VerifyError: Expecting a stack map frame ... @84
```

### 原因

第一版想把「踢人」的分支改成永远不执行：

```java
// 原始: if (hasExtendedEnums()) { disconnect(); return; }
// 想法: 把 ifne 换成 POP + GOTO，直接跳过断连块
```

结果原断连块变成**不可达代码**。而 JVM 校验器（`-Xverify:all` 时尤其严格）要求：**每一条指令，包括不可达的，都必须在 stackmap table 里有对应的 frame**。

你保留了那段字节码，但它的 frame 位置对不上了 → `VerifyError`。

### 修法

**不要改原有控制流**，改成在方法入口插早退：

```java
// 方法最开头插入：
if (listener.getConnectionType().isOther()) {
    listener.finishCurrentTask(TYPE);
    return;
}
// 原方法体一个字不动，仍然可达，frame 全部有效
```

只给**新插入的跳转目标**补 frame：

```java
Label cont = new Label();
... 插入的判断和早退 ...
target.visitLabel(cont);
target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);   // ← 补上
```

`F_SAME` 意思是「这一点的栈和局部变量状态与上一条 frame 相同」—— 大多数插入点用这个就够。

### 通用原则

> **能加早退就别改控制流。**

早退不改原有 frame 结构，风险最低。改控制流则要重新推演所有分支的栈状态。

---

## 坑 4：异常表区间不能为空

### 现象

```
java.lang.ClassFormatError: Illegal exception table range in class file
```

### 原因

想给某条指令加 try/catch，第一版这样写：

```java
Label start = new Label(), end = new Label(), handler = new Label();
target.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
target.visitLabel(start);
target.visitMethodInsn(...);          // 被保护的指令
target.visitLabel(end);               // ← 终点紧跟着起点
target.visitLabel(handler);
```

异常表算出来 `from == to`，即**保护区间长度为 0**，JVM 直接拒绝。

### 修法

在 `end` 之前**多放一条指令**，让区间非空：

```java
target.visitLabel(start);
target.visitMethodInsn(...);           // 原指令
target.visitJumpInsn(Opcodes.GOTO, safe);   // ← 新增，让区间覆盖两条指令
target.visitLabel(end);
target.visitLabel(handler);
... catch 处理 ...
target.visitLabel(safe);
```

实测生成的异常表：

```
Exception table:
   from    to  target type
     56    64      64   Class java/lang/Throwable
```

`from=56, to=64` → 覆盖 56 和 60 两条指令（invoke + goto），长度 8 字节，合法。

### 为什么不能补 NOP

试过在 `end` 处补一条 `NOP`。问题是：新指令通过 `target.visitXxx()` 写出时，会**落在旧指令之间**（因为 ASM 是按回调顺序写字节的），结果 `end` 标签的实际位置还是在 `start` 后面紧挨着 → 还是 `from == to`。

必须用「换写法」—— 把原指令重新写一遍，后面跟 GOTO，让原来的下一条指令自然成为 `safe` 的目标。

---

## 坑 5：重发指令会多压栈

### 现象

```
java.lang.NegativeArraySizeException: -3
```

### 原因

想在 `visitMethodInsn` 里「先保护再执行」：

```java
@Override
public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
    super.visitMethodInsn(opcode, owner, name, desc, itf);   // 发一次
    // ... 插 try/catch ...
    super.visitMethodInsn(opcode, owner, name, desc, itf);   // 又发一次 ← 灾难
}
```

`transformIncoming` 有返回值，发两次就压了两个值到栈上。后面的 `out.add()` 只消费一个，栈失衡 → `COMPUTE_FRAMES` 算出负数。

### 修法

二选一：

- **只插 tryCatchBlock，不动原指令**（如果原指令后面本来就有别的指令，区间自然非空）
- **走坑 4 的「换写法」**：不调 `super`，自己用 `target.visitXxx` 完整重写那一段

绝不要「super 发一次 + target 再发一次」。

---

## 坑 6：ASM 版本要跟得上 JDK

### 现象

```
java.lang.IllegalArgumentException: Unsupported class file major version 65
```

### 原因

`major version 65` = **Java 21**。ASM 9.3 只认到 Java 19（major 63）。

### 修法

用 **ASM 9.10.1** 或更新。本项目用 9.10.1。

```bash
javac -cp asm-9.10.1.jar -d . PatchNeoForge.java
```

ASM 版本 ↔ 支持的 class 文件版本：

| ASM | 最高 Java |
|---|---|
| 9.3 | 19 |
| 9.6 | 21 |
| 9.10 | 25 |

---

## 坑 7：`VerifyPatch` 的 classpath 末尾必须有 `.`

### 现象

```
错误: 找不到或无法加载主类 VerifyPatch
```

### 原因

```bash
CP="neoforge-patched.jar:$(find libraries -name '*.jar' | tr '\n' ':')"
java -Xverify:all -cp "$CP" VerifyPatch
```

`$(find ... | tr '\n' ':')` 的结尾**本来就是一个冒号**，但 `CP` 赋值后没有 `.`（当前目录）。`VerifyPatch.class` 在 `./` 下，不在任何 jar 里。

### 修法

```bash
CP="neoforge-patched.jar:$(find libraries -name '*.jar' | tr '\n' ':')."
#                                                                      ↑ 注意这个点
```

或者干脆 `-cp "$CP:."`。

这个坑不致命（补丁本身已经生成好了），但会让你误以为补丁失败。

---

## 为什么不用 Mixin

试过了，**不行**。

### 尝试

写了个 Mixin 模组 `vanillapass`：

```json
{
  "required": true,
  "package": "com.example.vanillapass.mixin",
  "mixins": ["NetworkComponentNegotiatorMixin"],
  "refmap": "vanillapass.refmap.json"
}
```

```java
@Mixin(NetworkComponentNegotiator.class)
public class NetworkComponentNegotiatorMixin {
    @Inject(method = "negotiate", at = @At("HEAD"), cancellable = true)
    private static void onNegotiate(List<NetworkComponent> server, List<NetworkComponent> client,
                                     CallbackInfoReturnable<NegotiationResult> cir) {
        if (client.isEmpty()) cir.setReturnValue(new NegotiationResult(List.of(), true, Map.of()));
    }
}
```

### 结果

Mixin 配置**加载成功**（日志有 `Selecting config vanillapass.mixins.json` / `Preparing vanillapass.mixins.json (1)`），但目标类**一个字节都没变**。

用 `-Dmixin.debug.export=true` 导出 `.mixin.out`，里面**根本没有** `NetworkComponentNegotiator.class`。

### 原因

NeoForge 自己的类由 **FML 的 classloader 在模组加载之前**就加载完了。等 Mixin 有机会介入时，这些类已经在内存里，不会再被读一次。

`remap = false` 试过，`-Dmixin.debug.verbose=true` 也试过，都没用。

### 教训

> **模组 Mixin 只能改「模组加载之后才加载的类」，改不了加载器自己的类。**

这类需求只能直接改 jar。

### 编译时还有个坑

```bash
javac ... 
# 报: Unable to locate obfuscation mapping for @Inject target negotiate
```

Mixin 的注解处理器要 obfuscation mapping。加 `-proc:none` 关掉处理器即可。

---

## 调试方法论

改字节码最容易陷入「改了不知道有没有生效」。这套流程能省很多时间：

### 1. 先 `javap` 看清原始字节码

```bash
javap -c -p -cp target.jar com.example.TargetClass | head -80
```

重点看：方法描述符、局部变量槽位、指令偏移。补丁里写死的数字（如 `astore 8`、`aload 3`）都从这里来。

### 2. 补丁程序要输出「命中报告」

```java
System.out.println("patch negotiator=" + p1 + " extensibleEnums=" + p2
        + " featureFlags=" + p3 + " checkPacket=" + p4);
if (!(p1 && p2 && p3 && p4)) {
    System.err.println("有补丁点没命中，jar 结构可能不对");
    System.exit(2);
}
```

没命中就 `exit 2`，别让它「静默成功」。

### 3. 改完立刻 `javap` 验证

```bash
javap -c -p -cp patched.jar com.example.TargetClass | grep -A2 'checkPacket'
# 应看到: 0: return
```

### 4. 用 `-Xverify:all` 加载

```bash
java -Xverify:all -cp "$CP" VerifyPatch
```

`-Xverify:all` 会校验**所有**类，包括没被直接调用的。默认的 `-Xverify:remote` 会跳过本地类，可能漏掉问题。

### 5. 从原件重新生成，不要原地改

```bash
# 每次都从 .orig 生成，别在打过补丁的 jar 上再打一次
java -cp "asm-9.10.1.jar:." PatchNeoForge neoforge-universal.jar.orig /tmp/patched.jar
cp /tmp/patched.jar mcserver/libraries/.../neoforge-21.1.250-universal.jar
```

原地改会叠加补丁，第二次跑就找不到 `astore 8` 了（因为它已经被前面的补丁改过）。

### 6. 服务端日志只信 `latest.log`，别信客户端提示

客户端显示「无效的玩家数据」，服务端日志里才是 `Payload carryon:sync_scripts may not be sent to the client!`。

**永远先看服务端日志。**
