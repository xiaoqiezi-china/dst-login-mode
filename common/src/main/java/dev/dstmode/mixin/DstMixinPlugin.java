package dev.dstmode.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 加载期开关：只有检测到认证配置时才应用任何注入。
 * 未配置时对游戏完全惰性，不修改任何原版类字节码。
 */
public final class DstMixinPlugin implements IMixinConfigPlugin {
    private static final String PROP_AUTH_BASE = "dstmode.authBase";
    private static final String ENV_AUTH_BASE = "DSTMODE_AUTH_BASE";
    private static final String PROP_BOOTSTRAP = "dstmode.bootstrap";
    private static final String ENV_BOOTSTRAP = "DSTMODE_BOOTSTRAP";

    private final boolean active = detectConfigured();

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[DST] mixin active=" + active);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return active;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean detectConfigured() {
        if (nonBlank(System.getProperty(PROP_AUTH_BASE))
                || nonBlank(System.getenv(ENV_AUTH_BASE))
                || nonBlank(System.getProperty(PROP_BOOTSTRAP))
                || nonBlank(System.getenv(ENV_BOOTSTRAP))) {
            return true;
        }
        try {
            Path path = Path.of(System.getProperty("user.dir", "."), "config", "dstmode.json");
            if (!Files.isRegularFile(path)) {
                return false;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.matches("(?s).*\"(authBase|bootstrap)\"\s*:\s*\"[^\"]+\".*");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}