package dev.dstmode.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 只重写 authlib 的类。authlib 是未混淆的库，类名与方法名跨版本稳定，
 * 因此不需要按 Minecraft 版本分支。
 *
 * <p>做三件事：</p>
 * <ol>
 *   <li>把 {@code https://sessionserver.mojang.com} 换成签发服务器地址</li>
 *   <li>{@code TextureUrlChecker.isAllowedTextureDomain} 恒为 true（放行自有域名）</li>
 *   <li>{@code getPropertySignatureState} 恒返回 SIGNED（让其他玩家能看到皮肤）</li>
 * </ol>
 */
final class AuthlibTransformer implements ClassFileTransformer {
    private static final String AUTHLLIB_PREFIX = "com/mojang/authlib/";
    private static final String MOJANG_SESSION_HTTPS = "https://sessionserver.mojang.com";
    private static final String MOJANG_SESSION_HTTP = "http://sessionserver.mojang.com";
    private static final String TEXTURE_CHECKER = "com/mojang/authlib/yggdrasil/TextureUrlChecker";
    private static final String SESSION_SERVICE = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String SIGNATURE_STATE = "com/mojang/authlib/SignatureState";

    private final String authBase;

    AuthlibTransformer(String authBase) {
        this.authBase = authBase;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !className.startsWith(AUTHLLIB_PREFIX) || classfileBuffer == null) {
            return null;
        }
        if (className.indexOf("sessionserver") < 0
                && !TEXTURE_CHECKER.equals(className)
                && !SESSION_SERVICE.equals(className)
                && !containsMojangSessionUrl(classfileBuffer)) {
            return null;
        }
        try {
            return rewrite(className, classfileBuffer);
        } catch (Throwable t) {
            System.out.println("[DST] agent: skip " + className + " (" + t + ")");
            return null;
        }
    }

    private static boolean containsMojangSessionUrl(byte[] bytes) {
        return indexOf(bytes, MOJANG_SESSION_HTTPS) >= 0 || indexOf(bytes, MOJANG_SESSION_HTTP) >= 0;
    }

    private static int indexOf(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + target.length <= haystack.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private byte[] rewrite(String className, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        boolean[] changed = {false};

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (delegate == null) {
                    return null;
                }
                if (TEXTURE_CHECKER.equals(className)
                        && "isAllowedTextureDomain".equals(name)
                        && "(Ljava/lang/String;)Z".equals(descriptor)) {
                    changed[0] = true;
                    delegate.visitCode();
                    delegate.visitInsn(Opcodes.ICONST_1);
                    delegate.visitInsn(Opcodes.IRETURN);
                    delegate.visitMaxs(1, 64);
                    delegate.visitEnd();
                    return null;
                }
                if (SESSION_SERVICE.equals(className)
                        && "getPropertySignatureState".equals(name)
                        && descriptor.endsWith(")L" + SIGNATURE_STATE + ";")) {
                    changed[0] = true;
                    delegate.visitCode();
                    delegate.visitFieldInsn(Opcodes.GETSTATIC, SIGNATURE_STATE, "SIGNED", "L" + SIGNATURE_STATE + ";");
                    delegate.visitInsn(Opcodes.ARETURN);
                    delegate.visitMaxs(1, 64);
                    delegate.visitEnd();
                    return null;
                }
                return new UrlRewriter(delegate, changed);
            }
        };

        reader.accept(visitor, 0);
        return changed[0] ? writer.toByteArray() : null;
    }

    private String rewriteUrl(String value) {
        if (value.startsWith(MOJANG_SESSION_HTTPS)) {
            return authBase + value.substring(MOJANG_SESSION_HTTPS.length());
        }
        if (value.startsWith(MOJANG_SESSION_HTTP)) {
            return authBase + value.substring(MOJANG_SESSION_HTTP.length());
        }
        return value;
    }

    /** 改写字符串常量。 */
    private final class UrlRewriter extends MethodVisitor {
        private final boolean[] changed;

        UrlRewriter(MethodVisitor delegate, boolean[] changed) {
            super(Opcodes.ASM9, delegate);
            this.changed = changed;
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String) {
                String text = (String) value;
                String replaced = rewriteUrl(text);
                if (!replaced.equals(text)) {
                    changed[0] = true;
                    value = replaced;
                }
            }
            super.visitLdcInsn(value);
        }
    }

}