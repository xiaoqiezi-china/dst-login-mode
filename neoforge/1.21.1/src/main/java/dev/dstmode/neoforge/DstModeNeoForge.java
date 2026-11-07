package dev.dstmode.neoforge;

import dev.dstmode.DstCommands;
import dev.dstmode.DstConfig;
import dev.dstmode.DstLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** NeoForge 入口。客户端与服务端共用同一个 jar。 */
@Mod(DstModeNeoForge.MOD_ID)
public final class DstModeNeoForge {
    public static final String MOD_ID = "dstmode";

    public DstModeNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        DstConfig.setGameDir(FMLPaths.GAMEDIR.get());

        String base = DstConfig.authBase();
        if (base == null) {
            DstLog.boot("inactive side={}", FMLEnvironment.dist);
        } else {
            DstLog.boot("active side={} authBase={} src={}", FMLEnvironment.dist, base, DstConfig.source());
        }
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        DstCommands.register(event.getDispatcher());
    }
}
