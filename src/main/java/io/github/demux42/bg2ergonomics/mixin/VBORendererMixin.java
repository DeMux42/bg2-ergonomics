package io.github.demux42.bg2ergonomics.mixin;

import io.github.demux42.bg2ergonomics.client.GadgetControls;
import com.direwolf20.buildinggadgets2.client.renderer.VBORenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Copy/Cut selection outline in BG2's place, client side only. BG2 draws it from the item, which only
 * changes after the server has applied a scroll step, so it trailed the wheel. The add-on draws it where the wheel
 * has put it ({@link GadgetControls#drawCopyBox}). If BG2 changes this method, the game logs it and keeps BG2's.
 */
@Mixin(VBORenderer.class)
public abstract class VBORendererMixin {
    @Inject(method = "drawCopyBox", at = @At("HEAD"), cancellable = true)
    private static void bg2ergonomics$drawCopyBox(PoseStack matrix, ItemStack gadget, String mode, CallbackInfo ci) {
        GadgetControls.drawCopyBox(matrix, gadget, mode);
        ci.cancel();
    }
}
