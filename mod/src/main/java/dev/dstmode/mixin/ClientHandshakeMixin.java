package dev.dstmode.mixin;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import dev.dstmode.DstAuth;
import dev.dstmode.DstLog;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * 客户端登录：优先把 join 发到签发服务器，失败则回退原版 Mojang。
 *
 * <p>保证「装了模组 + 配置了 authBase」的正版玩家仍然能登录：
 * Mojang 的 accessToken 是 JWT（eyJ 开头），识别到即直接走原版，不产生额外往返；
 * 其余情况先试签发服务器，被拒后回退原版。</p>
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeMixin {

    private static boolean isMojangToken(String token) {
        return token != null && token.length() > 32 && token.startsWith("eyJ");
    }

    @Redirect(
            method = "authenticateServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;joinServer(Ljava/util/UUID;Ljava/lang/String;Ljava/lang/String;)V"
            )
    )
    private void dstmode$join(MinecraftSessionService service, UUID profileId, String accessToken, String serverId)
            throws AuthenticationException {
        if (!DstAuth.enabled() || isMojangToken(accessToken)) {
            service.joinServer(profileId, accessToken, serverId);
            return;
        }

        try {
            DstAuth.join(profileId, accessToken, serverId);
            return;
        } catch (Throwable t) {
            DstLog.info("client", "join fallback -> mojang ({})", t.getClass().getSimpleName());
        }
        service.joinServer(profileId, accessToken, serverId);
    }
}
