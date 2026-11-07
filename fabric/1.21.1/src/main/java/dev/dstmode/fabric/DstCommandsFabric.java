package dev.dstmode.fabric;

import dev.dstmode.DstCommands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/** 与 Fabric API 的解耦层：类加载失败时由入口捕获，模组其余功能不受影响。 */
final class DstCommandsFabric {
    private DstCommandsFabric() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                DstCommands.register(dispatcher));
    }
}
