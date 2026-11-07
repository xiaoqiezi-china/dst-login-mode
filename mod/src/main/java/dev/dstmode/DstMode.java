package dev.dstmode;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DST-Mode 入口。同一个 jar 同时用于客户端与服务端。 */
@Mod(DstMode.MOD_ID)
public final class DstMode {
    public static final String MOD_ID = "dstmode";
    public static final Logger LOGGER = LoggerFactory.getLogger("DST-Mode");

    public DstMode(IEventBus modEventBus, ModContainer modContainer) {
        String base = DstConfig.authBase();
        if (base == null) {
            LOGGER.info("[DST] inactive side={}", FMLEnvironment.dist);
        } else {
            LOGGER.info("[DST] active side={} authBase={} src={}", FMLEnvironment.dist, base, DstConfig.source());
        }
        NeoForge.EVENT_BUS.addListener(DstCommands::onRegisterCommands);
    }
}
