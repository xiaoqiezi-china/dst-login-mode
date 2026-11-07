package dev.dstmode.mixin;

import dev.dstmode.DstConfig;
import dev.dstmode.DstLog;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 关闭「安全聊天」强制校验。
 *
 * <p>1.19.1+ 的聊天签名机制要求玩家持有 Mojang 签发的 profile 公钥。
 * 非正版玩家没有公钥，服务端 {@code enforceSecureProfile()} 返回 true 时，
 * {@code SignedMessageChain.Decoder.unsigned(...)} 会抛 MISSING_PROFILE_KEY，
 * 客户端表现就是「由于玩家档案公钥丢失，聊天已被禁用」。</p>
 *
 * <p>原版已经有 {@code enforce-secure-profile=false} 开关，但 DedicatedServer 里
 * 该开关只有在 {@code online-mode=true && services.canValidateProfileKeys()} 时才生效；
 * 本模组刻意保留 online-mode=true，所以这里直接把该判断截断为 false，
 * 等效于「无论配置如何都不强制安全聊天」。</p>
 *
 * <p>未配置 authBase 时不做任何事，保证纯正版服行为不变。</p>
 */
@Mixin(DedicatedServer.class)
public abstract class ChatEnforcementMixin {

    @Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true)
    private void dstmode$disableSecureProfile(CallbackInfoReturnable<Boolean> cir) {
        if (DstConfig.authBase() != null) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
