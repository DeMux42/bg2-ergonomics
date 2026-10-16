package io.github.demux42.bg2ergonomics.client;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import com.direwolf20.buildinggadgets2.client.KeyBindings;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static io.github.demux42.bg2ergonomics.client.GadgetControls.*;

/**
 * The held gadget's scroll controls, on the left edge of the screen. The row for the keys being held lights up, in
 * the colour of the side highlight it draws in the world (yellow: stretch that side, blue: slide toward it). Key
 * names follow the player's bindings.
 * <p>
 * Placed where modded HUDs rarely are: minimaps take the top corners (Xaero's top left; JourneyMap and FTB Chunks
 * top right, with potion effects), Jade and boss bars the top centre, chat the bottom left, subtitles the bottom
 * right, a server scoreboard the right edge. It keeps clear of the chat's lines at large GUI scales, and hides with
 * F1, F3, any open screen, and the checkbox in the gadget's G menu.
 */
final class ControlsOverlay {
    private static final int LEFT = 4, PAD = 4, ROW = 13, GAP = 8, CHAT_CLEARANCE = 140;
    private static final int TEXT = 0xFFD8D8D8, DIM = 0xFF8A8A8A, LIT = 0xFFFFFFFF, CAP = 0xFF5A5A5A;

    /**
     * One line: the keys (and "+ Scroll" when it is a scroll control), then what it does. {@code rgb} is the colour
     * of the world highlight it goes with, 0 for none.
     */
    private record Hint(List<KeyMapping> keys, boolean hold, boolean scroll, Component action, int rgb, boolean active) {
    }

    private ControlsOverlay() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()
                || !BG2Ergonomics.CONTROLS_OVERLAY.get()) return;
        List<Hint> hints = hints(BaseGadget.getGadget(mc.player));
        if (hints.isEmpty()) return;
        Font font = mc.font;
        Component footer = Component.translatable("bg2ergonomics.overlay.hide", keyName(KeyBindings.menuSettings));
        int keysWidth = 0, width = font.width(footer);
        for (Hint hint : hints) keysWidth = Math.max(keysWidth, keysWidth(font, hint));
        for (Hint hint : hints) width = Math.max(width, keysWidth + GAP + font.width(hint.action()));
        int height = PAD * 2 + hints.size() * ROW + font.lineHeight + 2;
        int top = Math.max(4, Math.min(g.guiHeight() / 2 - height / 2, g.guiHeight() - CHAT_CLEARANCE - height));

        g.fill(LEFT, top, LEFT + width + PAD * 2, top + height, 0x90000000);
        int x = LEFT + PAD, y = top + PAD;
        for (Hint hint : hints) {
            drawRow(g, font, hint, x, y, x + keysWidth + GAP);
            y += ROW;
        }
        g.drawString(font, footer, x, y + 2, DIM, false);
    }

    private static List<Hint> hints(ItemStack gadget) {
        boolean aim = held(AIM_KEY), slide = held(MOVE_KEY);
        List<Hint> hints = new ArrayList<>();
        switch (kind(gadget)) {
            case SELECTION -> {
                hints.add(scroll(List.of(AIM_KEY), "stretch", STRETCH_RGB, aim && !slide));
                hints.add(scroll(List.of(AIM_KEY, MOVE_KEY), "stretchopposite", STRETCH_RGB, aim && slide));
                hints.add(scroll(List.of(MOVE_KEY), "slide", SLIDE_RGB, slide && !aim));
            }
            case PASTE -> {
                boolean pinned = pinned(gadget);
                hints.add(scroll(List.of(AIM_KEY), "rotate", 0, aim && !slide));
                hints.add(scroll(List.of(MOVE_KEY), pinned ? "slide" : "slidepins", SLIDE_RGB, slide));
                if (pinned) hints.add(new Hint(List.of(KeyBindings.anchor), false, false, text("unpin"), 0, false));
            }
            case RANGE -> hints.add(new Hint(List.of(AIM_KEY), false, true, text("range", toolRange(gadget)), 0, aim));
            case DESTRUCTION -> {
                hints.add(scroll(List.of(AIM_KEY), "destructionside", 0, aim));
                hints.add(scroll(List.of(MOVE_KEY), "depth", 0, slide && !aim));
            }
            case NONE -> {
            }
        }
        if (hints.isEmpty()) return hints;
        hints.add(new Hint(List.of(BIG_STEP_KEY), true, false, text("bigsteps"), 0, held(BIG_STEP_KEY)));
        if (gadget.getItem() instanceof GadgetCopyPaste)
            hints.add(new Hint(List.of(ClientEvents.CLIPBOARD_KEY), false, false, text("clipboard"), 0, held(ClientEvents.CLIPBOARD_KEY)));
        return hints;
    }

    private static Hint scroll(List<KeyMapping> keys, String action, int rgb, boolean active) {
        return new Hint(keys, false, true, text(action), rgb, active);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("bg2ergonomics.overlay." + key, args);
    }

    private static int keysWidth(Font font, Hint hint) {
        int width = hint.hold() ? font.width(text("hold")) + 3 : 0;
        for (int i = 0; i < hint.keys().size(); i++)
            width += (i > 0 ? plusWidth(font) : 0) + font.width(keyName(hint.keys().get(i))) + 6;
        return width + (hint.scroll() ? plusWidth(font) + font.width(text("scroll")) : 0);
    }

    private static int plusWidth(Font font) {
        return font.width("+") + 4;
    }

    private static void drawRow(GuiGraphics g, Font font, Hint hint, int x, int y, int actionX) {
        int accent = hint.rgb() != 0 ? 0xFF000000 | hint.rgb() : LIT;
        int text = hint.active() ? accent : TEXT;
        if (hint.hold()) {
            g.drawString(font, text("hold"), x, y + 2, DIM, false);
            x += font.width(text("hold")) + 3;
        }
        for (int i = 0; i < hint.keys().size(); i++) {
            if (i > 0) x = plus(g, font, x, y);
            Component name = keyName(hint.keys().get(i));
            int capWidth = font.width(name) + 6;
            g.fill(x, y, x + capWidth, y + 11, hint.active() ? accent : CAP); //Keycap: border, then face
            g.fill(x + 1, y + 1, x + capWidth - 1, y + 10, 0xFF202020);
            g.drawString(font, name, x + 3, y + 2, text, false);
            x += capWidth;
        }
        if (hint.scroll()) g.drawString(font, text("scroll"), plus(g, font, x, y), y + 2, text, false);
        if (hint.rgb() != 0) g.fill(actionX - 5, y + 1, actionX - 3, y + 10, 0xFF000000 | hint.rgb()); //Same colour as its world highlight
        g.drawString(font, hint.action(), actionX, y + 2, hint.active() ? LIT : TEXT, false);
    }

    private static int plus(GuiGraphics g, Font font, int x, int y) {
        g.drawString(font, "+", x + 2, y + 2, DIM, false);
        return x + plusWidth(font);
    }

    /**
     * The binding's name, with the long vanilla names of the modifier keys shortened ("Left Control" to "Ctrl").
     */
    private static Component keyName(KeyMapping key) {
        if (key.getKeyModifier() == KeyModifier.NONE && key.getKey().getType() == InputConstants.Type.KEYSYM) {
            String shortName = switch (key.getKey().getValue()) {
                case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "Ctrl";
                case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "Alt";
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "Shift";
                default -> null;
            };
            if (shortName != null) return Component.literal(shortName);
        }
        return key.getTranslatedKeyMessage();
    }
}
