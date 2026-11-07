package dev.dstmode.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import dev.dstmode.DstAuth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** authlib 的 unpackTextures 只接受 *.minecraft.net，这里自行解包以支持自有材质域名。 */
@Mixin(targets = "net.minecraft.client.resources.SkinManager$1")
public abstract class SkinManagerMixin {

    @Redirect(
            method = "lambda$load$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;unpackTextures(Lcom/mojang/authlib/properties/Property;)Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;"
            )
    )
    private static MinecraftProfileTextures dstmode$unpack(MinecraftSessionService service, Property property) {
        MinecraftProfileTextures textures = DstAuth.unpackTextures(property);
        return textures != null ? textures : service.unpackTextures(property);
    }
}
