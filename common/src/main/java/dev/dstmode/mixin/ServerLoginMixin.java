package dev.dstmode.mixin;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import dev.dstmode.DstAuth;
import dev.dstmode.DstConfig;
import dev.dstmode.DstLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.InetAddress;

/**
 * 服务端登录校验：hasJoined 改为询问签发服务器（本地 QQ 票据优先 + 未命中转发上游）。
 *
 * <p>不向上游透传 ip：服务端位于 Docker/反代之后时 InetAddress 是网桥地址，
 * 与 Mojang 记录的客户端 IP 不一致，透传会导致正版玩家被 204 拒绝。</p>
 */
@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl$1")
public abstract class ServerLoginMixin {

    @Redirect(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;hasJoinedServer(Ljava/lang/String;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/yggdrasil/ProfileResult;"
            )
    )
    private ProfileResult dstmode$hasJoined(MinecraftSessionService service, String username, String serverId, InetAddress address)
            throws AuthenticationUnavailableException {
        if (!DstAuth.enabled()) {
            return service.hasJoinedServer(username, serverId, address);
        }
        DstLog.info("server", "hasJoined <- {}", username);
        return DstAuth.hasJoined(username, serverId, null);
    }
}
