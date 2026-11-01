import java.lang.reflect.Method;
import java.util.List;

/**
 * 自检：加载打过补丁的类，验证字节码能过校验器，并确认协商逻辑真的返回成功。
 *
 * 注意：CheckExtensibleEnums / CheckFeatureFlags 的静态初始化依赖 FML 运行环境
 * （ModList），在裸 JVM 里会抛 ExceptionInInitializerError —— 这是预期的，
 * 校验器（verify）在初始化之前就跑完了，所以只要不是 VerifyError 就算通过。
 *
 * 用法:
 *     CP="/path/to/neoforge-21.1.250-universal.jar:$(find libraries -name '*.jar' | tr '\n' ':')."
 *     java -Xverify:all -cp "$CP" VerifyPatch
 *
 * **注意 cp 末尾那个 `.`** —— 少了它就会报「找不到或无法加载主类 VerifyPatch」。
 */
public class VerifyPatch {
    public static void main(String[] args) {
        boolean failed = false;
        String[] guarded = {
                "net.neoforged.neoforge.network.configuration.CheckExtensibleEnums",
                "net.neoforged.neoforge.network.configuration.CheckFeatureFlags",
        };
        for (String name : guarded) {
            try {
                Class.forName(name);
                System.out.println("loaded+verified: " + name);
            } catch (VerifyError ve) {
                System.out.println("VERIFY ERROR in " + name + ": " + ve);
                failed = true;
            } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
                System.out.println("verified (init needs FML runtime, expected): " + name);
            } catch (Throwable t) {
                System.out.println("UNEXPECTED in " + name + ": " + t);
                failed = true;
            }
        }

        try {
            Class<?> c = Class.forName(
                    "net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator");
            Method m = c.getMethod("negotiate", List.class, List.class);
            Object result = m.invoke(null, List.of(), List.of());
            String text = String.valueOf(result);
            System.out.println("result = " + text);
            if (!text.contains("success=true")) {
                System.out.println("FAIL 空客户端通道没被判成功");
                failed = true;
            }
        } catch (Throwable t) {
            System.out.println("negotiate check threw: " + t);
            failed = true;
        }

        System.out.println(failed ? "SELF-TEST FAILED" : "SELF-TEST PASSED");
        if (failed) {
            System.exit(3);
        }
    }
}
