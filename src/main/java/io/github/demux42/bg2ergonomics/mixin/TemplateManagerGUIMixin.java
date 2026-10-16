package io.github.demux42.bg2ergonomics.mixin;

import io.github.demux42.bg2ergonomics.client.PreviewFit;
import io.github.demux42.bg2ergonomics.client.TemplatePreviewCache;
import com.direwolf20.buildinggadgets2.client.screen.TemplateManagerGUI;
import com.direwolf20.buildinggadgets2.common.containers.TemplateManagerContainer;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2DataClient;
import com.direwolf20.buildinggadgets2.util.FakeRenderingWorld;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Fixes BG2's Template Manager preview, client side only. If BG2 changes these methods the mixin config is not
 * required, so the game logs it and keeps BG2's own preview.
 * <ul>
 *     <li>Frame rate: BG2 rebuilt the preview world and rescanned the template on every frame; both are now built
 *     once per template ({@link TemplatePreviewCache}).</li>
 *     <li>Centring: the camera now turns around the template's centre and backs off far enough to show all of it,
 *     with the panel's own aspect ratio ({@link PreviewFit}). BG2's drag, zoom and reset behave as before.</li>
 *     <li>Zoom: every wheel step counts, even several within one tick.</li>
 * </ul>
 */
@Mixin(TemplateManagerGUI.class)
public abstract class TemplateManagerGUIMixin extends AbstractContainerScreen<TemplateManagerContainer> {
    @Unique
    private static final float BG2ERGONOMICS$FOV = 60; //BG2's

    @Shadow
    @Final
    private Rect2i panel;
    @Shadow
    private float rotX, rotY, zoom, panX, panY, initZoom;
    @Shadow
    private int renderSlot;
    @Shadow
    @Final
    private TemplateManagerContainer container;

    @Shadow
    public abstract void drawRenderScreen(PoseStack matrix, Player player, ArrayList<StatePos> statePosCache);

    private TemplateManagerGUIMixin(TemplateManagerContainer menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "renderPanel", at = @At("HEAD"), cancellable = true)
    private void bg2ergonomics$renderPanel(GuiGraphics guiGraphics, CallbackInfo ci) {
        ci.cancel();
        ItemStack gadget = container.getSlot(renderSlot).getItem();
        if (gadget.isEmpty()) return;
        ArrayList<StatePos> blocks = BG2DataClient.getLookupFromUUID(GadgetNBT.getUUID(gadget));
        if (blocks == null || blocks.isEmpty()) return;
        PreviewFit fit = PreviewFit.of(blocks.get(0).pos, blocks.get(blocks.size() - 1).pos, BG2ERGONOMICS$FOV);

        Window window = minecraft.getWindow();
        double scale = window.getGuiScale();
        RenderSystem.viewport((int) Math.round((leftPos + panel.getX()) * scale),
                (int) Math.round(window.getHeight() - (topPos + panel.getY() + panel.getHeight()) * scale),
                (int) Math.round(panel.getWidth() * scale), (int) Math.round(panel.getHeight() * scale));
        RenderSystem.backupProjectionMatrix();
        //The panel's aspect ratio, not the whole screen's, so the preview is not stretched
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(BG2ERGONOMICS$FOV), (float) panel.getWidth() / panel.getHeight(),
                0.1f, Math.max(1000, fit.distance() + fit.radius() * 2));
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.setIdentity();
        pose.translate(panX * fit.step(), -panY * fit.step(), -fit.distance() + zoom * fit.step()); //Pan and zoom in view space, like BG2
        pose.mulPose(Axis.XP.rotationDegrees(rotX));
        pose.mulPose(Axis.YP.rotationDegrees(rotY));
        pose.translate(-fit.centre().x, -fit.centre().y, -fit.centre().z); //Turn around the centre, not a corner
        RenderSystem.applyModelViewMatrix();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        RenderSystem.runAsFancy(() -> drawRenderScreen(pose, minecraft.player, blocks));
        pose.popPose();

        RenderSystem.applyModelViewMatrix();
        RenderSystem.viewport(0, 0, window.getWidth(), window.getHeight());
        RenderSystem.restoreProjectionMatrix();
    }

    @Redirect(method = "drawRenderScreen", at = @At(value = "NEW", target = "com/direwolf20/buildinggadgets2/util/FakeRenderingWorld"))
    private FakeRenderingWorld bg2ergonomics$cachedWorld(Level level, ArrayList<StatePos> blocks, BlockPos origin) {
        return TemplatePreviewCache.world(level, blocks, origin);
    }

    @Redirect(method = "drawRenderScreen", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;toList()Ljava/util/List;"))
    private List<StatePos> bg2ergonomics$cachedBlockEntityBlocks(Stream<StatePos> filtered) {
        return TemplatePreviewCache.blockEntityBlocks(filtered);
    }

    /**
     * BG2 sets zoom from the value saved at the last tick, so several wheel steps within one tick count as one.
     * Starting each step from the current zoom makes every step count.
     */
    @Inject(method = "mouseScrolled", at = @At("HEAD"))
    private void bg2ergonomics$everyWheelStep(double mouseX, double mouseY, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        initZoom = zoom;
    }
}
