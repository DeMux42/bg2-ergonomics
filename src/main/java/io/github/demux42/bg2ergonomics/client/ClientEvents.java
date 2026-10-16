package io.github.demux42.bg2ergonomics.client;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardEntry;
import io.github.demux42.bg2ergonomics.network.ClipboardActionPayload;
import io.github.demux42.bg2ergonomics.network.MirrorPayload;
import com.direwolf20.buildinggadgets2.client.KeyBindings;
import com.direwolf20.buildinggadgets2.client.screen.ModeRadialMenu;
import com.direwolf20.buildinggadgets2.client.screen.TemplateManagerGUI;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClientEvents {
    //V as in Windows' Win+V clipboard history. Same "holding a gadget" context and Controls category as BG2's own keys
    public static final KeyMapping CLIPBOARD_KEY = new KeyMapping("key.bg2ergonomics.clipboard", new KeyBindings.KeyConflictContextGadget(),
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V), "key.buildinggadgets2.category");
    //Unbound by default, so they take no key from anyone; the mode wheel has buttons for them
    public static final KeyMapping MIRROR_KEY = new KeyMapping("key.bg2ergonomics.mirror", new KeyBindings.KeyConflictContextGadget(),
            InputConstants.UNKNOWN, "key.buildinggadgets2.category");
    public static final KeyMapping FLIP_KEY = new KeyMapping("key.bg2ergonomics.flip", new KeyBindings.KeyConflictContextGadget(),
            InputConstants.UNKNOWN, "key.buildinggadgets2.category");
    private static final String EXTRA = "buildinggadgetsextra"; //Building Gadgets Extra puts its own mirror buttons on the wheel

    private static List<ClipboardEntry> clipboard = List.of();
    private static UUID heldEntry;

    //Tap the clipboard key for the list; hold it and scroll to flip through copies with BG2's paste preview showing each
    private static final int FLIP_DELAY_TICKS = 3; //Wait for the wheel to settle before asking the server, like the list screen
    private static boolean clipboardKeyDown;
    private static boolean usedWhileDown;
    private static UUID flipTarget;
    private static UUID flipQueued;
    private static int flipDelay;

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> List.of(CLIPBOARD_KEY, MIRROR_KEY, FLIP_KEY).forEach(event::register));
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(BG2Ergonomics.MODID, "scroll_controls"), ControlsOverlay::render));
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClipboardThumbnails::onRenderFrame);
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> {
            if (event.getScreen() instanceof TemplateManagerGUI) TemplatePreviewCache.clear();
        });
        GadgetControls.init(modBus);
    }

    /**
     * This player's history, newest first, as last sent by the server.
     */
    public static List<ClipboardEntry> clipboard() {
        return clipboard;
    }

    /**
     * The entry the held gadget holds, as last sent by the server; null for none or an unsaved copy.
     */
    public static UUID heldEntry() {
        return heldEntry;
    }

    public static void setClipboard(List<ClipboardEntry> entries, UUID held) {
        clipboard = List.copyOf(entries);
        heldEntry = ClipboardActionPayload.NONE.equals(held) ? null : held;
        ClipboardThumbnails.sync(clipboard);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean down = mc.player != null && mc.screen == null && GadgetControls.held(CLIPBOARD_KEY);
        if (down && !clipboardKeyDown) {
            pressClipboardKey();
        } else if (!down && clipboardKeyDown) {
            clipboardKeyDown = false;
            flushFlip();
            if (!usedWhileDown && mc.screen == null) openClipboard(); //A tap; a hold that scrolled keeps what it flipped to
        }
        if (flipQueued != null && --flipDelay <= 0) flushFlip();
        if (mc.player != null) ClipboardThumbnails.tick();
        while (MIRROR_KEY.consumeClick()) mirror(false);
        while (FLIP_KEY.consumeClick()) mirror(true);
    }

    /**
     * Mirrors the held Copy/Paste or Cut/Paste copy: left to right as you face, or upside down. Done on the server.
     */
    private static void mirror(boolean vertical) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mirrorable(BaseGadget.getGadget(mc.player).getItem())) return;
        if (!serverHas(MirrorPayload.TYPE)) {
            say(Component.translatable("bg2ergonomics.message.noservermirror"));
            return;
        }
        PacketDistributor.sendToServer(new MirrorPayload(vertical));
    }

    private static boolean mirrorable(Item item) {
        return item instanceof GadgetCopyPaste || item instanceof GadgetCutPaste;
    }

    private static void pressClipboardKey() {
        clipboardKeyDown = true;
        usedWhileDown = false;
        flipTarget = null;
    }

    /**
     * The key already did its job (the list screen closed on it), so letting go of it must not open the list again.
     */
    public static void clipboardKeyUsed() {
        clipboardKeyDown = true;
        usedWhileDown = true;
    }

    /**
     * Scrolling with the clipboard key held loads the next older (wheel down) or newer (wheel up) copy into the
     * gadget, so BG2's paste preview shows it. Returns whether the scroll was used.
     */
    static boolean onScroll(ItemStack gadget, int direction) {
        if (!(gadget.getItem() instanceof GadgetCopyPaste) || !GadgetControls.held(CLIPBOARD_KEY)) return false;
        if (!clipboardKeyDown) pressClipboardKey(); //Scrolled before the next tick saw the key go down
        usedWhileDown = true;
        if (!serverHas(ClipboardActionPayload.TYPE)) {
            say(Component.translatable("bg2ergonomics.message.noserver"));
            return true;
        }
        //From an unsaved copy (index -1) the first step down is the newest entry; the server saves the copy first
        int index = ClipboardEntry.indexOf(clipboard, flipTarget != null ? flipTarget : heldEntry) + (direction < 0 ? 1 : -1);
        if (index < 0 || index >= clipboard.size()) return true;
        ClipboardEntry entry = clipboard.get(index);
        flipTarget = flipQueued = entry.id();
        flipDelay = FLIP_DELAY_TICKS;
        say(Component.translatable("bg2ergonomics.message.loaded", index + 1, clipboard.size(), entry.blockCount()));
        return true;
    }

    private static void flushFlip() {
        if (flipQueued != null && !flipQueued.equals(heldEntry))
            PacketDistributor.sendToServer(new ClipboardActionPayload(ClipboardActionPayload.Action.LOAD, flipQueued));
        flipQueued = null;
    }

    static boolean serverHas(CustomPacketPayload.Type<?> payload) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(payload);
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(message, true);
    }

    private static void openClipboard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(BaseGadget.getGadget(mc.player).getItem() instanceof GadgetCopyPaste)) return;
        if (!serverHas(ClipboardActionPayload.TYPE)) {
            say(Component.translatable("bg2ergonomics.message.noserver"));
            return;
        }
        mc.setScreen(new ClipboardGUI());
    }

    /**
     * Adds a row under BG2's mode wheel: Mirror, Clipboard, Flip, and below it the scroll-controls overlay
     * checkbox. BG2's own buttons are left alone.
     */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ModeRadialMenu screen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Item item = BaseGadget.getGadget(mc.player).getItem();
        boolean mirror = mirrorable(item) && !ModList.get().isLoaded(EXTRA);
        List<Button> row = new ArrayList<>();
        if (mirror) row.add(button(Component.translatable("bg2ergonomics.button.mirror"), () -> mirror(false)));
        if (item instanceof GadgetCopyPaste)
            row.add(button(Component.translatable("bg2ergonomics.button.clipboard", CLIPBOARD_KEY.getTranslatedKeyMessage()), ClientEvents::openClipboard));
        if (mirror) row.add(button(Component.translatable("bg2ergonomics.button.flip"), () -> mirror(true)));

        int gap = 4, x = screen.width / 2 - (row.stream().mapToInt(Button::getWidth).sum() + gap * (row.size() - 1)) / 2;
        for (Button button : row) {
            button.setPosition(x, screen.height / 2 + 72); //Below the wheel, where BG2 puts its range slider on other gadgets
            x += button.getWidth() + gap;
            event.addListener(button);
        }

        Checkbox overlay = Checkbox.builder(Component.translatable("bg2ergonomics.checkbox.overlay"), mc.font)
                .selected(BG2Ergonomics.CONTROLS_OVERLAY.get())
                .onValueChange((box, on) -> {
                    BG2Ergonomics.CONTROLS_OVERLAY.set(on);
                    BG2Ergonomics.CONTROLS_OVERLAY.save();
                }).build();
        overlay.setPosition(screen.width / 2 - overlay.getWidth() / 2, screen.height / 2 + 96); //Below the button row or BG2's range slider
        event.addListener(overlay);
    }

    private static Button button(Component label, Runnable action) {
        return Button.builder(label, b -> action.run()).size(Math.max(70, Minecraft.getInstance().font.width(label) + 12), 20).build();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clipboard = List.of();
        heldEntry = null;
        flipTarget = flipQueued = null;
        ClipboardThumbnails.clear();
    }
}
