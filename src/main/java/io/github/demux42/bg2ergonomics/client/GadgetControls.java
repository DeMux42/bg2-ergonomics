package io.github.demux42.bg2ergonomics.client;

import io.github.demux42.bg2ergonomics.CopyLimits;
import io.github.demux42.bg2ergonomics.controls.Aim;
import io.github.demux42.bg2ergonomics.controls.Box;
import io.github.demux42.bg2ergonomics.controls.DestructionSize;
import io.github.demux42.bg2ergonomics.controls.Glide;
import com.direwolf20.buildinggadgets2.client.KeyBindings;
import com.direwolf20.buildinggadgets2.client.renderer.MyRenderMethods;
import com.direwolf20.buildinggadgets2.client.renderer.OurRenderTypes;
import com.direwolf20.buildinggadgets2.common.items.*;
import com.direwolf20.buildinggadgets2.common.network.data.*;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2DataClient;
import com.direwolf20.buildinggadgets2.setup.BG2DataComponents;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.VectorHelper;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Scroll-wheel controls for every BG2 gadget, client side only. Everything goes through BG2's own packets, so it
 * works on a server that has plain BG2. Only active while a gadget is held and a modifier is down; plain scrolling
 * still changes the hotbar slot. Every control acts on the side you point toward, read like a radial menu over the
 * box ({@link Aim}), so what a scroll does never depends on which way you face. {@link ControlsOverlay} lists them.
 * <ul>
 *     <li>Aim key (Ctrl) + scroll: Copy/Cut stretches the side you aim at (kept until the keys are released), Paste
 *     rotates, Building/Exchanging changes range, Destruction changes the side under the crosshair.</li>
 *     <li>Aim + slide keys (Ctrl+Alt) + scroll: Copy/Cut stretches the side opposite the one you aim at, for sides
 *     you cannot see.</li>
 *     <li>Slide key (Alt) + scroll: moves the whole selection or paste toward the side you aim at; Destruction
 *     depth.</li>
 *     <li>Big-step key (X) held: steps of 10. Not Shift, which descends while flying.</li>
 * </ul>
 * Wheel up always moves the aimed side outward.
 */
public class GadgetControls {
    public static final KeyMapping AIM_KEY = key("aim", GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping MOVE_KEY = key("move", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping BIG_STEP_KEY = key("bigstep", GLFW.GLFW_KEY_X);

    static final int STRETCH_RGB = 0xFFEE44, SLIDE_RGB = 0x55DDFF; //Face highlight colours, also used by the overlay
    private static final int MAX_RANGE = 15; //BG2's R key and range slider
    private static final int SELECTION_SEND_GAP = 3; //Ticks between selection sends: BG2 re-reads every block in it each time

    enum Kind {SELECTION, PASTE, RANGE, DESTRUCTION, NONE}

    private static final Pending<Box> selection = new Pending<>(SELECTION_SEND_GAP, box -> send(new CopyCoordsPayload(box.start(), box.end())));
    private static final Pending<BlockPos> pasteOffset = new Pending<>(0, offset -> send(new RelativePastePayload(offset)));
    private static final Pending<Integer> range = new Pending<>(0, value -> send(new RangeChangePayload(value)));
    private static final Pending<DestructionSize> destruction = new Pending<>(0, size -> send(
            new DestructionRangesPayload(size.left(), size.right(), size.up(), size.down(), size.depth())));

    private static Direction lockedFace; //The side being scrolled; kept while a modifier is down so it can leave the crosshair
    private static PasteLock pasteLock;
    private static UUID glideGadget; //The selection outline's ease: see shownBounds
    private static AABB glideFrom, glideTo;
    private static long glideStart;
    private static long stepAt; //Last scroll step, for the face flash

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.bg2ergonomics." + name, new KeyBindings.KeyConflictContextGadget(),
                InputConstants.Type.KEYSYM.getOrCreate(key), "key.buildinggadgets2.category");
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> List.of(AIM_KEY, MOVE_KEY, BIG_STEP_KEY).forEach(event::register));
        NeoForge.EVENT_BUS.addListener(GadgetControls::onScroll);
        NeoForge.EVENT_BUS.addListener(GadgetControls::onClientTick);
        NeoForge.EVENT_BUS.addListener(GadgetControls::onRenderLevel);
    }

    /**
     * Whether the key is physically down right now, in its context. Read from the keyboard rather than from
     * {@link KeyMapping#isDown()}, because these keys share Ctrl/Alt/X with vanilla on purpose and NeoForge treats a
     * held modifier as blocking other keys.
     */
    public static boolean held(KeyMapping key) {
        if (key.isUnbound() || !key.getKeyConflictContext().isActive()) return false;
        InputConstants.Key input = key.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();
        boolean down = input.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, input.getValue()) == GLFW.GLFW_PRESS
                : InputConstants.isKeyDown(window, input.getValue());
        return down && key.getKeyModifier().isActive(key.getKeyConflictContext());
    }

    static Kind kind(ItemStack gadget) {
        if (gadget.getItem() instanceof GadgetCopyPaste || gadget.getItem() instanceof GadgetCutPaste) {
            String mode = GadgetNBT.getMode(gadget).getId().getPath();
            return mode.equals("paste") ? Kind.PASTE : mode.equals("copy") || mode.equals("cut") ? Kind.SELECTION : Kind.NONE;
        }
        if (gadget.getItem() instanceof GadgetBuilding || gadget.getItem() instanceof GadgetExchanger) return Kind.RANGE;
        if (gadget.getItem() instanceof GadgetDestruction) return Kind.DESTRUCTION;
        return Kind.NONE;
    }

    /**
     * Read-only: {@link GadgetNBT#getUUID} would write a new id into the client's copy of the item.
     */
    private static UUID id(ItemStack gadget) {
        return gadget.isEmpty() ? null : gadget.get(BG2DataComponents.GADGET_UUID);
    }

    static boolean pinned(ItemStack gadget) {
        return !GadgetNBT.getAnchorPos(gadget).equals(GadgetNBT.nullPos);
    }

    static int toolRange(ItemStack gadget) {
        return range.get(id(gadget), GadgetNBT.getToolRange(gadget));
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int direction = (int) Math.signum(event.getScrollDeltaY());
        if (player == null || mc.screen != null || direction == 0) return;
        ItemStack gadget = BaseGadget.getGadget(player);
        if (gadget.isEmpty()) return;
        if (ClientEvents.onScroll(gadget, direction)) { //Clipboard key held: flip through the history
            event.setCanceled(true);
            return;
        }
        boolean aim = held(AIM_KEY), slide = held(MOVE_KEY);
        if (!aim && !slide) return;
        int steps = direction * (held(BIG_STEP_KEY) ? 10 : 1);
        UUID id = id(gadget);
        boolean ours = switch (kind(gadget)) {
            case SELECTION -> scrollSelection(player, gadget, id, aim, slide, steps);
            case PASTE -> scrollPaste(player, gadget, id, aim, slide, direction, steps);
            case RANGE -> aim && scrollRange(gadget, id, steps); //The slide key does nothing here, so leave its scroll to the hotbar
            case DESTRUCTION -> scrollDestruction(player, gadget, id, aim, steps);
            case NONE -> false;
        };
        if (ours) event.setCanceled(true);
    }

    private static boolean scrollSelection(LocalPlayer player, ItemStack gadget, UUID id, boolean aim, boolean slide, int steps) {
        Box box = selection.get(id, Box.of(gadget));
        if (box == null) {
            say("bg2ergonomics.message.noselection");
            return true;
        }
        if (lockedFace == null) lockedFace = aimedFace(player, box.bounds(), 1f);
        if (lockedFace == null) {
            say("bg2ergonomics.message.aimselection");
            return true;
        }
        Box next = !aim ? box.move(lockedFace, steps) : box.pushFace(slide ? lockedFace.getOpposite() : lockedFace, steps);
        if (next.equals(box)) return true;
        int maxBlocks = gadget.getItem() instanceof GadgetCopyPaste ? CopyLimits.copyBlocks() : CopyLimits.BG2_BLOCKS;
        if (!next.fitsBG2(maxBlocks)) {
            say("bg2ergonomics.message.toolarge", maxBlocks, CopyLimits.AXIS);
            return true;
        }
        selection.set(id, next);
        stepAt = System.nanoTime();
        say("bg2ergonomics.message.selection", next.size(Direction.Axis.X), next.size(Direction.Axis.Y), next.size(Direction.Axis.Z), next.blocks());
        return true;
    }

    private static boolean scrollPaste(LocalPlayer player, ItemStack gadget, UUID id, boolean aim, boolean slide, int direction, int steps) {
        if (!slide) { //BG2 only turns one way, so three turns go back one
            for (int turns = direction > 0 ? 1 : 3; turns > 0; turns--)
                send(RotatePayload.INSTANCE);
            return true;
        }
        AABB bounds = pasteBounds(player, gadget, id);
        if (bounds == null) {
            say("bg2ergonomics.message.aimfirst");
            return true;
        }
        if (lockedFace == null) lockedFace = aimedFace(player, bounds, 1f);
        if (lockedFace == null) {
            say("bg2ergonomics.message.aimpaste");
            return true;
        }
        if (!lockPaste(player, gadget, id)) return true;
        pasteOffset.set(id, pasteOffset.get(id, GadgetNBT.getRelativePaste(gadget)).relative(lockedFace, steps));
        stepAt = System.nanoTime();
        return true;
    }

    /**
     * Where BG2 shows the paste preview, as VBORenderer.drawRender places it, with the offset the wheel has set.
     * Null while BG2 shows none: no copy yet, or not anchored and not looking at a block.
     */
    private static AABB pasteBounds(Player player, ItemStack gadget, UUID id) {
        List<StatePos> blocks = id == null ? null : BG2DataClient.getLookupFromUUID(id);
        if (blocks == null || blocks.isEmpty()) return null;
        BlockPos base = GadgetNBT.getAnchorPos(gadget);
        if (base.equals(GadgetNBT.nullPos)) {
            base = VectorHelper.getLookingAt(player, gadget).getBlockPos();
            if (player.level().getBlockState(base).isAir()) return null;
        }
        BlockPos at = base.above().offset(pasteOffset.get(id, GadgetNBT.getRelativePaste(gadget)));
        //ponytail: a scan of the whole copy per call (per frame while the slide key is held); cache by copy if huge copies stutter
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StatePos block : blocks) {
            BlockPos p = block.pos;
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).move(at.getX(), at.getY(), at.getZ());
    }

    /**
     * The first slide pins the paste where it is shown, with BG2's own anchor, so it stops following the
     * crosshair. BG2's anchor key (H) releases it, and {@link #tickPasteLock} then puts the old offset back.
     */
    private static boolean lockPaste(LocalPlayer player, ItemStack gadget, UUID id) {
        if (pinned(gadget) || (pasteLock != null && Objects.equals(pasteLock.gadget, id)))
            return true;
        BlockHitResult hit = VectorHelper.getLookingAt(player, gadget);
        if (player.level().getBlockState(hit.getBlockPos()).isAir()) { //BG2 anchors only on a block, and shows no paste without one
            say("bg2ergonomics.message.aimfirst");
            return false;
        }
        pasteLock = new PasteLock(id, pasteOffset.get(id, GadgetNBT.getRelativePaste(gadget)));
        send(AnchorPayload.INSTANCE);
        return true;
    }

    private static boolean scrollRange(ItemStack gadget, UUID id, int steps) {
        int current = range.get(id, GadgetNBT.getToolRange(gadget)), next = Math.clamp(current + steps, 1, MAX_RANGE);
        if (next != current) range.set(id, next); //BG2 answers with its own "range set" message
        return true;
    }

    private static boolean scrollDestruction(LocalPlayer player, ItemStack gadget, UUID id, boolean aim, int steps) {
        BlockHitResult hit = VectorHelper.getLookingAt(player, gadget);
        boolean anchored = pinned(gadget);
        if (!anchored && player.level().getBlockState(hit.getBlockPos()).isAir()) return true;
        Direction anchorSide = GadgetNBT.getAnchorSide(gadget);
        Direction face = anchored && anchorSide != null ? anchorSide : hit.getDirection(); //The face BG2 builds the area from
        DestructionSize.Side side = aim
                ? DestructionSize.aimed(face, player.getDirection(), hit.getLocation().subtract(Vec3.atCenterOf(hit.getBlockPos())))
                : new DestructionSize.Side(GadgetNBT.IntSettings.DEPTH, 1);
        DestructionSize current = destruction.get(id, DestructionSize.of(gadget)), next = current.adjust(side.setting(), side.sign() * steps);
        if (!next.equals(current)) destruction.set(id, next);
        say("bg2ergonomics.message.destruction", Component.translatable("buildinggadgets2.screen." + side.setting().getName()),
                next.get(side.setting()), next.dimensions());
        return true;
    }

    /**
     * The side of the box the crosshair points toward, read like a radial menu ({@link Aim}).
     */
    private static Direction aimedFace(Player player, AABB bounds, float partialTick) {
        return Aim.face(player.getEyePosition(partialTick), player.getViewVector(partialTick), bounds);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!held(AIM_KEY) && !held(MOVE_KEY)) lockedFace = null;
        ItemStack gadget = BaseGadget.getGadget(player);
        UUID id = id(gadget);
        selection.tick(id, Box.of(gadget));
        pasteOffset.tick(id, GadgetNBT.getRelativePaste(gadget));
        range.tick(id, GadgetNBT.getToolRange(gadget));
        destruction.tick(id, DestructionSize.of(gadget));
        tickPasteLock(gadget, id);
    }

    private static void tickPasteLock(ItemStack gadget, UUID id) {
        if (pasteLock == null || !Objects.equals(pasteLock.gadget, id)) return;
        if (pinned(gadget)) {
            pasteLock.confirmed = true;
        } else if (pasteLock.confirmed) { //Released (H, or a Cut paste clears it): follow the crosshair again with the old offset
            pasteOffset.set(id, pasteLock.restoreOffset);
            pasteLock = null;
        } else if (++pasteLock.age > Pending.GIVE_UP_TICKS) { //The server never anchored it
            pasteLock = null;
        }
    }

    /**
     * Highlights the side the wheel will act on: yellow when it stretches that side, blue when the whole thing
     * slides that way. It flashes on each step and fades ({@link Glide#pulse}).
     */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        boolean aim = held(AIM_KEY), slide = held(MOVE_KEY);
        if (!aim && !slide) return;
        ItemStack gadget = BaseGadget.getGadget(mc.player);
        UUID id = id(gadget);
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        AABB bounds, aimBounds;
        Kind kind = kind(gadget);
        if (kind == Kind.SELECTION) {
            Box box = selection.get(id, Box.of(gadget));
            if (box == null) return;
            bounds = shownBounds(id, box);
            aimBounds = box.bounds();
        } else if (kind == Kind.PASTE && slide) {
            bounds = aimBounds = pasteBounds(mc.player, gadget, id);
            if (bounds == null) return;
        } else {
            return;
        }
        Direction face = lockedFace != null ? lockedFace : aimedFace(mc.player, aimBounds, partialTick);
        if (face == null) return;
        boolean stretch = kind == Kind.SELECTION && aim;
        if (stretch && slide) face = face.getOpposite();

        AABB slab = faceSlab(bounds, face);
        float flash = (float) Glide.pulse((System.nanoTime() - stepAt) / 1e6);
        int rgb = stretch ? STRETCH_RGB : SLIDE_RGB;
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        PoseStack matrix = event.getPoseStack();
        Vec3 view = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        matrix.pushPose();
        matrix.translate(-view.x, -view.y, -view.z);
        MyRenderMethods.renderBoxSolid(matrix.last(), matrix.last().pose(), buffer, slab.minX, slab.minY, slab.minZ, slab.maxX, slab.maxY, slab.maxZ,
                r + (1 - r) * 0.75f * flash, g + (1 - g) * 0.75f * flash, b + (1 - b) * 0.75f * flash, 0.3f + 0.35f * flash); //White-hot on impact
        buffer.endBatch(OurRenderTypes.TRANSPARENT_BOX);
        matrix.popPose();
    }

    /**
     * Draws the Copy/Cut outline in BG2's place (VBORendererMixin), where the wheel has put it rather than where
     * the server has caught up to, in BG2's colours.
     */
    public static void drawCopyBox(PoseStack matrix, ItemStack gadget, String mode) {
        UUID id = id(gadget);
        Box box = selection.get(id, Box.of(gadget));
        if (box == null) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 view = mc.gameRenderer.getMainCamera().getPosition();
        float green = mode.equals("copy") ? 1 : 0; //Green for Copy, red for Cut
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        matrix.pushPose();
        matrix.translate(-view.x, -view.y, -view.z);
        LevelRenderer.renderLineBox(matrix, buffer.getBuffer(OurRenderTypes.lines()), shownBounds(id, box), 1 - green, green, 0, 1);
        buffer.endBatch(OurRenderTypes.lines());
        matrix.popPose();
    }

    /**
     * Where the selection outline is this frame: easing toward the newest box ({@link Glide}). Wall-clock based, so
     * the outline and the face highlight agree within a frame and the frame rate does not change the feel.
     */
    private static AABB shownBounds(UUID id, Box box) {
        long now = System.nanoTime();
        AABB target = box.bounds();
        if (glideTo == null || !Objects.equals(id, glideGadget)) { //New gadget or selection: appear in place
            glideGadget = id;
            glideFrom = glideTo = target;
            glideStart = now - 1_000_000_000L;
        } else if (!target.equals(glideTo)) {
            glideFrom = Glide.at(glideFrom, glideTo, (now - glideStart) / 1e6);
            glideTo = target;
            glideStart = now;
        }
        return Glide.at(glideFrom, glideTo, (now - glideStart) / 1e6);
    }

    private static AABB faceSlab(AABB bounds, Direction face) {
        Direction.Axis axis = face.getAxis();
        double at = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? bounds.max(axis) : bounds.min(axis), e = 0.02;
        AABB grown = bounds.inflate(e);
        return switch (axis) {
            case X -> new AABB(at - e, grown.minY, grown.minZ, at + e, grown.maxY, grown.maxZ);
            case Y -> new AABB(grown.minX, at - e, grown.minZ, grown.maxX, at + e, grown.maxZ);
            case Z -> new AABB(grown.minX, grown.minY, at - e, grown.maxX, grown.maxY, at + e);
        };
    }

    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    private static void say(String key, Object... args) {
        Player player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static final class PasteLock {
        final UUID gadget;
        final BlockPos restoreOffset;
        boolean confirmed;
        int age;

        PasteLock(UUID gadget, BlockPos restoreOffset) {
            this.gadget = gadget;
            this.restoreOffset = restoreOffset;
        }
    }

    /**
     * A value sent to BG2 that the held item does not show yet. The next scroll step builds on it, so a fast wheel
     * never loses steps to the server round trip. {@code minGap} spaces out sends that cost the server real work;
     * the newest value always goes out.
     */
    private static final class Pending<T> {
        static final int GIVE_UP_TICKS = 40; //The server refused or changed it: go back to what the item says

        private final int minGap;
        private final Consumer<T> sender;
        private UUID gadget;
        private T value;
        private boolean unsent;
        private int sinceSend = 1000, age;

        Pending(int minGap, Consumer<T> sender) {
            this.minGap = minGap;
            this.sender = sender;
        }

        T get(UUID heldGadget, T onItem) {
            return value != null && Objects.equals(heldGadget, gadget) ? value : onItem;
        }

        void set(UUID heldGadget, T next) {
            gadget = heldGadget;
            value = next;
            unsent = true;
            age = 0;
            flushIfDue();
        }

        void tick(UUID heldGadget, T onItem) {
            sinceSend = Math.min(sinceSend + 1, 1000);
            if (value == null) return;
            if (!Objects.equals(heldGadget, gadget)) { //BG2 applies its packets to whatever is held now, so never send this to another gadget
                value = null;
                unsent = false;
            } else if (unsent) {
                flushIfDue();
            } else if (value.equals(onItem) || ++age > GIVE_UP_TICKS) {
                value = null;
            }
        }

        private void flushIfDue() {
            if (unsent && sinceSend >= minGap) {
                sender.accept(value);
                unsent = false;
                sinceSend = 0;
            }
        }
    }
}
