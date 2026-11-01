import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 让原版客户端（含 Geyser 基岩桥接）能连进 NeoForge 服务端。
 *
 * 四道关卡，四段补丁。前三段只影响「客户端通道为空 / 连接类型是 Other」这条原版分支，
 * 正常的 NeoForge 客户端之间的流程完全不受影响；第四段是兜底，见下。
 *
 * 1) NetworkComponentNegotiator.negotiate(server, client)
 *      if (client.isEmpty()) return new NegotiationResult(List.of(), true, Map.of());
 *    原来这里会因为服务端存在「必选通道」直接判协商失败并被踢，
 *    客户端看到 "You are trying to connect to a server that is running NeoForge, but you are not."
 *
 * 2) CheckExtensibleEnums.start(consumer)   —— 配置阶段的「扩展枚举」守卫
 * 3) CheckFeatureFlags.start(consumer)      —— 配置阶段的「自定义 FeatureFlag」守卫
 *    这两个守卫的逻辑都是「服务端声明了扩展枚举 / 自定义 FeatureFlag 就踢原版客户端」。
 *    补丁在方法入口插一段早退：连接类型是 Other 时直接 finishCurrentTask(TYPE) 返回，
 *    等价于「假装列表为空」。**不能**改成把原断连分支变成不可达代码 —— 那样 verifier
 *    会抱怨不可达块缺 stackmap frame。
 *
 * 4) NetworkRegistry.checkPacket(...)       —— 兜底：整方法替换为 return
 *    模组在玩家进世界那一刻会发自己私有的 payload，NeoForge 见到「没协商过的 payload」
 *    就抛 UnsupportedOperationException("Payload xxx may not be sent to the client!")，
 *    整条连接被打断，客户端只会看到「无效的玩家数据 / Invalid player data」，极难定位。
 *    改成立 empty 之后这些 payload 会照常发出去，原版客户端对未知 payload 一律静默忽略。
 *
 * 用法:
 *     javac -cp asm-9.10.1.jar -d . PatchNeoForge.java
 *     java  -cp "asm-9.10.1.jar:." PatchNeoForge <原版universal.jar> <输出jar>
 *
 * 输出应为:
 *     patch negotiator=true extensibleEnums=true featureFlags=true checkPacket=true
 */
public class PatchNeoForge {

    static final String NEGOTIATOR =
            "net/neoforged/neoforge/network/negotiation/NetworkComponentNegotiator";
    static final String RESULT =
            "net/neoforged/neoforge/network/negotiation/NegotiationResult";
    static final String NEG_DESC =
            "(Ljava/util/List;Ljava/util/List;)Lnet/neoforged/neoforge/network/negotiation/NegotiationResult;";

    static final String EXT_ENUMS =
            "net/neoforged/neoforge/network/configuration/CheckExtensibleEnums";
    static final String FEATURE_FLAGS =
            "net/neoforged/neoforge/network/configuration/CheckFeatureFlags";
    static final String NETWORK_REGISTRY =
            "net/neoforged/neoforge/network/registration/NetworkRegistry";
    static final String START_DESC = "(Ljava/util/function/Consumer;)V";

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        boolean p1 = false, p2 = false, p3 = false, p4 = false;

        try (JarFile jf = new JarFile(in.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                byte[] data;
                try (InputStream is = jf.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                String n = entry.getName();
                if (n.equals(NEGOTIATOR + ".class")) {
                    data = patchNegotiator(data);
                    p1 = true;
                } else if (n.equals(EXT_ENUMS + ".class") || n.equals(FEATURE_FLAGS + ".class")) {
                    data = patchVanillaGuard(data);
                    if (n.equals(EXT_ENUMS + ".class")) {
                        p2 = true;
                    } else {
                        p3 = true;
                    }
                } else if (n.equals(NETWORK_REGISTRY + ".class")) {
                    data = patchCheckPacket(data);
                    p4 = true;
                }
                JarEntry ne = new JarEntry(n);
                ne.setTime(entry.getTime());
                jos.putNextEntry(ne);
                jos.write(data);
                jos.closeEntry();
            }
        }
        System.out.println("patch negotiator=" + p1 + " extensibleEnums=" + p2
                + " featureFlags=" + p3 + " checkPacket=" + p4);
        if (!(p1 && p2 && p3 && p4)) {
            System.err.println("有补丁点没命中，jar 结构可能不对");
            System.exit(2);
        }
    }

    /** 补丁 1：空客户端通道直接判协商成功。 */
    static byte[] patchNegotiator(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                final MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"negotiate".equals(name) || !NEG_DESC.equals(desc)) {
                    return target;
                }
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label skip = new Label();
                        target.visitVarInsn(Opcodes.ALOAD, 1);          // client
                        target.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                                "isEmpty", "()Z", true);
                        target.visitJumpInsn(Opcodes.IFEQ, skip);
                        target.visitTypeInsn(Opcodes.NEW, RESULT);
                        target.visitInsn(Opcodes.DUP);
                        // List.of() / Map.of() 是接口静态方法，isInterface 必须为 true
                        target.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List",
                                "of", "()Ljava/util/List;", true);
                        target.visitInsn(Opcodes.ICONST_1);
                        target.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map",
                                "of", "()Ljava/util/Map;", true);
                        target.visitMethodInsn(Opcodes.INVOKESPECIAL, RESULT, "<init>",
                                "(Ljava/util/List;ZLjava/util/Map;)V", false);
                        target.visitInsn(Opcodes.ARETURN);
                        target.visitLabel(skip);
                        target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 补丁 4：NetworkRegistry.checkPacket(...) 改为空操作。
     *
     * 原来这里会抛 UnsupportedOperationException("Payload xxx may not be sent to the client!")，
     * 直接把整条连接打断。改成不拦之后，模组发出的 payload 会照常送到原版客户端，
     * 而原版客户端对未知 payload 一律静默忽略。
     */
    static byte[] patchCheckPacket(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                // 注意：这里万不能用名为 mv 的局部变量 —— MethodVisitor 自己有个继承来的
                // protected 字段 mv（委托），匿名类里会解析到那个 null 字段上去。
                final MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"checkPacket".equals(name)) {
                    return target;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitCode() {
                        target.visitCode();
                        target.visitInsn(Opcodes.RETURN);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        target.visitMaxs(0, 0);
                    }

                    @Override
                    public void visitEnd() {
                        target.visitEnd();
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /** 补丁 2/3：原版连接（ConnectionType.isOther()）直接结束该配置任务，跳过两个踢人守卫。 */
    static byte[] patchVanillaGuard(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            private String currentClass;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                currentClass = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                final MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"start".equals(name) || !START_DESC.equals(desc)) {
                    return target;
                }
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label cont = new Label();
                        // if (!listener.getConnectionType().isOther()) goto cont;
                        target.visitVarInsn(Opcodes.ALOAD, 0);
                        target.visitFieldInsn(Opcodes.GETFIELD, currentClass,
                                "listener",
                                "Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;");
                        target.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                                "net/minecraft/network/protocol/configuration/ServerConfigurationPacketListener",
                                "getConnectionType",
                                "()Lnet/neoforged/neoforge/network/connection/ConnectionType;", true);
                        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/neoforged/neoforge/network/connection/ConnectionType",
                                "isOther", "()Z", false);
                        target.visitJumpInsn(Opcodes.IFEQ, cont);
                        // listener.finishCurrentTask(TYPE); return;
                        target.visitVarInsn(Opcodes.ALOAD, 0);
                        target.visitFieldInsn(Opcodes.GETFIELD, currentClass,
                                "listener",
                                "Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;");
                        target.visitFieldInsn(Opcodes.GETSTATIC, currentClass, "TYPE",
                                "Lnet/minecraft/server/network/ConfigurationTask$Type;");
                        target.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                                "net/minecraft/network/protocol/configuration/ServerConfigurationPacketListener",
                                "finishCurrentTask",
                                "(Lnet/minecraft/server/network/ConfigurationTask$Type;)V", true);
                        target.visitInsn(Opcodes.RETURN);
                        target.visitLabel(cont);
                        target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
