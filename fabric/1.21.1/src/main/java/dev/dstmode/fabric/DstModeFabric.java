package dev.dstmode.fabric;

import dev.dstmode.DstConfig;
import dev.dstmode.DstLog;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric 入口。客户端与服务端共用同一个 jar。 */
public final class DstModeFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DstConfig.setGameDir(FabricLoader.getInstance().getGameDir());

        String base = DstConfig.authBase();
        String side = FabricLoader.getInstance().isModLoaded("fabric-api") ? "universal" : "universal";
        if (base == null) {
            DstLog.boot("inactive side={}", side);
        } else {
            DstLog.boot("active side={} authBase={} src={}", side, base, DstConfig.source());
        }

        // 命令注册依赖 Fabric API；缺失时静默跳过，不影响登录与皮肤功能
        try {
            DstCommandsFabric.register();
        } catch (Throwable t) {
            DstLog.bootWarn("commands unavailable ({})", t.getClass().getSimpleName());
        }
    }
}
