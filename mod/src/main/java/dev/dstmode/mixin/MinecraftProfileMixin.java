package dev.dstmode.mixin;

import com.mojang.authlib.GameProfile;
import dev.dstmode.DstAuth;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 客户端启动时用签发服务器的 profile 替换本地 profile（单机/多人皮肤来源）。 */
@Mixin(Minecraft.class)
public abstract class MinecraftProfileMixin {

    @Inject(method = "getGameProfile", at = @At("RETURN"), cancellable = true)
    private void dstmode$profile(CallbackInfoReturnable<GameProfile> cir) {
        GameProfile original = cir.getReturnValue();
        if (original == null || !DstAuth.enabled()) {
            return;
        }
        GameProfile cached = DstAuth.profileBlocking(original.getId());
        if (cached != null) {
            DstAuth.logReplacedOnce(cached);
            cir.setReturnValue(cached);
        }
    }
}
