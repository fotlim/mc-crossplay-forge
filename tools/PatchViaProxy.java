import org.objectweb.asm.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * 给 ViaProxy.jar 里的 ViaVersion 打两个补丁。
 *
 * 补丁 A —— 配方包空指针
 *     com/viaversion/viaversion/protocols/v1_21to1_21_2/rewriter/BlockItemPacketRewriter1_21_2
 *     :lambda$registerPackets$8 处理 UPDATE_RECIPES 时，用
 *     `recipeSerializerMappings.identifier(serializerId)` 拿配方的序列化器名字；
 *     服务端装了加自定义配方类型的模组时这里返回 null，接着 `Key.stripMinecraftNamespace(null)`
 *     抛 NullPointerException，整条连接被掐掉。
 *     补丁在拿到 identifier 之后插：
 *         if (identifier == null) { wrapper.cancel(); return; }
 *     也就是「这一包配方里有认不出来的配方，整包不发」，连接继续。
 *
 * 补丁 B —— 解码失败即断线
 *     com/viaversion/viaversion/platform/ViaCodecHandler.decode 把 transformIncoming()
 *     抛出的任何异常直接往外扔，Netty 随即断掉整条连接。典型场景：
 *         IllegalArgumentException: No data component serializer found for id 143
 *         （CONTAINER_SET_CONTENT —— 模组物品带的私有数据组件，ViaVersion 不认）
 *     补丁在「调 transformIncoming」和「把结果塞进 out」之间插一个 catch:
 *         catch (Throwable t) { out.clear(); return; }
 *     即放弃这一个包，连接留着。（官方更彻底的做法是开
 *     --ignore-protocol-translation-errors，见 README「五、开关」。）
 *
 * 两个补丁都只改 private/lambda 方法，共用一个 ClassWriter；因为插入了新的跳转目标，
 * 必须用 COMPUTE_FRAMES 重算栈帧，而帧计算需要解析类层次 —— 所以额外挂一个
 * URLClassLoader 指向正在处理的 jar 自己。
 *
 * 用法:
 *     javac -cp asm-9.10.1.jar -d . PatchViaProxy.java
 *     java  -cp "asm-9.10.1.jar:." PatchViaProxy <原版ViaProxy.jar> <输出jar>
 *
 * 输出应为: patched=true
 */
public class PatchViaProxy {

    static final String RECIPE =
            "com/viaversion/viaversion/protocols/v1_21to1_21_2/rewriter/BlockItemPacketRewriter1_21_2.class";
    static final String RECIPE_METHOD = "lambda$registerPackets$8";
    static final String CODEC =
            "com/viaversion/viaversion/platform/ViaCodecHandler.class";
    static final String DECODE_DESC = "(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V";
    static final String WRAPPER = "com/viaversion/viaversion/api/protocol/packet/PacketWrapper";
    static final String USER_CONNECTION = "com/viaversion/viaversion/api/connection/UserConnection";

    static boolean hitRecipe = false;
    static boolean hitDecode = false;

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(in));
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                byte[] data = readAll(zis);
                if (e.getName().equals(RECIPE)) {
                    data = patchRecipe(data, in);
                } else if (e.getName().equals(CODEC)) {
                    data = patchDecode(data, in);
                }
                ZipEntry n = new ZipEntry(e.getName());
                n.setTime(e.getTime());
                zos.putNextEntry(n);
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.println("patched=" + (hitRecipe && hitDecode)
                + " recipe=" + hitRecipe + " decode=" + hitDecode);
        if (!(hitRecipe && hitDecode)) {
            System.err.println("没找到补丁点，ViaVersion 版本可能变了");
            System.exit(2);
        }
    }

    /** 用 jar 自己解析类层次，供 COMPUTE_FRAMES 计算合并类型。 */
    static class JarClassWriter extends ClassWriter {
        private final ClassLoader loader;

        JarClassWriter(ClassReader cr, ClassLoader loader) {
            super(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                if (c1.isAssignableFrom(c2)) {
                    return type1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return type2;
                }
                if (c1.isInterface() || c2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    c1 = c1.getSuperclass();
                } while (!c1.isAssignableFrom(c2));
                return c1.getName().replace('.', '/');
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }

    static ClassWriter newWriter(byte[] original, Path jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, PatchViaProxy.class.getClassLoader());
        return new JarClassWriter(new ClassReader(original), loader);
    }

    // ------------------------------------------------------------------ 补丁 A

    static byte[] patchRecipe(byte[] original, Path jar) throws Exception {
        ClassWriter cw = newWriter(original, jar);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                final MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
                if (!RECIPE_METHOD.equals(name)) {
                    return target;
                }
                return new MethodVisitor(Opcodes.ASM9, target) {
                    boolean done = false;

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        super.visitVarInsn(opcode, var);
                        // 紧跟 `astore 8`（identifier）之后插入判空
                        if (!done && opcode == Opcodes.ASTORE && var == 8) {
                            done = true;
                            Label ok = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 8);
                            super.visitJumpInsn(Opcodes.IFNONNULL, ok);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, WRAPPER, "cancel", "()V", true);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(ok);
                            hitRecipe = true;
                        }
                    }
                };
            }
        };
        new ClassReader(original).accept(cv, 0);
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ 补丁 B

    static byte[] patchDecode(byte[] original, Path jar) throws Exception {
        ClassWriter cw = newWriter(original, jar);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                final MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"decode".equals(name) || !DECODE_DESC.equals(desc)) {
                    return target;
                }
                return new MethodVisitor(Opcodes.ASM9, target) {
                    // decode 的局部变量表（javap 确认过）：
                    //   0 this, 1 ctx, 2 in(ByteBuf), 3 out(List), 4 copy(ByteBuf), 5 Throwable
                    //
                    // 做法：把这条 invokeinterface 换成「同一段代码 + 一个紧跟其后的 GOTO」，
                    // 原来那条的下一条指令在 visitLabel(safe) 处成为跳转目标。
                    // 于是异常区间 [start, end) = {invoke, goto} 非空且合法，
                    // 且被保护的仍然只是那一条会抛的调用。
                    // **不能**在 end 处补 NOP —— 新指令写进 target 时会落在旧指令之间，
                    // 异常表区间算出来是 from==to，JVM 报 Illegal exception table range。
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String mdesc, boolean isInterface) {
                        if (opcode != Opcodes.INVOKEINTERFACE
                                || !USER_CONNECTION.equals(owner)
                                || !"transformIncoming".equals(mname)) {
                            super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                            return;
                        }
                        Label start = new Label();
                        Label end = new Label();
                        Label handler = new Label();
                        Label safe = new Label();
                        target.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                        target.visitLabel(start);
                        target.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                        target.visitJumpInsn(Opcodes.GOTO, safe);
                        target.visitLabel(end);
                        target.visitLabel(handler);
                        // catch (Throwable t) { out.clear(); return; }
                        target.visitVarInsn(Opcodes.ALOAD, 3);
                        target.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
                        target.visitInsn(Opcodes.RETURN);
                        target.visitLabel(safe);
                        hitDecode = true;
                    }
                };
            }
        };
        new ClassReader(original).accept(cv, 0);
        return cw.toByteArray();
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
