package io.github.demux42.bg2ergonomics.client;

import io.github.demux42.bg2ergonomics.clipboard.ClipboardEntry;
import io.github.demux42.bg2ergonomics.network.ClipboardActionPayload;
import io.github.demux42.bg2ergonomics.network.ClipboardActionPayload.Action;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/**
 * Clipboard history for the Copy/Paste gadget, as a grid of pictures, newest first: it is for picking a recent
 * copy by sight, fast. Moving the selection loads that entry into the gadget, so the paste preview in the world
 * behind this screen shows it: browse with the wheel or arrow keys, keep one with Enter, a click, or its number
 * key (1-9), back out with Esc.
 */
public class ClipboardGUI extends Screen {
    private static final int COLUMNS = 4;
    private static final int TILE = 58; //Picture size
    private static final int CELL_W = 62, CELL_H = TILE + 12; //Picture plus its caption
    private static final int PANEL_WIDTH = COLUMNS * CELL_W;
    private static final int MAX_ROWS = ClipboardThumbnails.MAX_SHOWN / COLUMNS;
    private static final int LOAD_DELAY_TICKS = 3; //Wait for the wheel to settle before asking the server

    private final List<ClipboardEntry> listAtOpen = ClientEvents.clipboard();
    private boolean requestedSave = false;
    private int rows = 1;
    private int selected = -1;
    private int firstRow = 0;
    private UUID originalEntry;
    private boolean originalCaptured = false;
    private UUID queuedLoad;
    private UUID lastRequested;
    private int loadDelay;
    private boolean confirmClear = false;
    private Button clearButton;

    public ClipboardGUI() {
        super(Component.translatable("bg2ergonomics.screen.clipboardheading"));
    }

    private int left() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int gridTop() {
        return (height - (rows * CELL_H + 86)) / 2 + 22;
    }

    private int gridBottom() {
        return gridTop() + rows * CELL_H;
    }

    private List<ClipboardEntry> entries() {
        return ClientEvents.clipboard();
    }

    @Override
    protected void init() {
        if (!requestedSave) { //init also runs on window resize
            //The server saves the gadget's unsaved copy (if any) and answers with a fresh list
            PacketDistributor.sendToServer(new ClipboardActionPayload(Action.SAVE, ClipboardActionPayload.NONE));
            requestedSave = true;
        }
        rows = Math.clamp((height - 100) / CELL_H, 1, MAX_ROWS);
        if (selected >= 0) select(selected, false); //Keep the selection in view at the new size
        int buttonY = gridBottom() + 14;
        addRenderableWidget(Button.builder(Component.translatable("bg2ergonomics.screen.delete"), b -> deleteSelected())
                .bounds(left(), buttonY, 70, 20).build());
        clearButton = addRenderableWidget(Button.builder(Component.translatable("bg2ergonomics.screen.clearall"), b -> clearAll())
                .bounds(left() + PANEL_WIDTH / 2 - 45, buttonY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("bg2ergonomics.screen.confirm"), b -> keep())
                .bounds(left() + PANEL_WIDTH - 70, buttonY, 70, 20).build());
    }

    @Override
    public void tick() {
        ItemStack gadget = getGadget();
        if (!(gadget.getItem() instanceof GadgetCopyPaste)) {
            onClose();
            return;
        }
        List<ClipboardEntry> entries = entries();
        if (selected < 0 && entries != listAtOpen && !entries.isEmpty()) { //First answer from the server: start on what the gadget holds
            int current = ClipboardEntry.indexOf(entries, ClientEvents.heldEntry());
            select(Math.max(current, 0), false);
        }
        if (selected >= entries.size())
            select(entries.size() - 1, false);
        if (queuedLoad != null && --loadDelay <= 0)
            flushLoad();
    }

    private ItemStack getGadget() {
        return minecraft == null || minecraft.player == null ? ItemStack.EMPTY : BaseGadget.getGadget(minecraft.player);
    }

    private void select(int index, boolean load) {
        List<ClipboardEntry> entries = entries();
        if (entries.isEmpty()) {
            selected = -1;
            return;
        }
        index = Math.clamp(index, 0, entries.size() - 1);
        if (load) {
            UUID target = entries.get(index).id();
            if (!target.equals(heldOrRequested())) {
                if (!originalCaptured) { //Remember what to go back to on Esc
                    UUID held = ClientEvents.heldEntry();
                    originalEntry = held != null ? held : GadgetNBT.hasCopyUUID(getGadget()) ? entries.get(0).id() : null;
                    originalCaptured = true;
                }
                queuedLoad = target;
                loadDelay = LOAD_DELAY_TICKS;
            } else {
                queuedLoad = null; //Back on the entry the gadget already has
            }
        }
        selected = index;
        int row = selected / COLUMNS;
        if (row < firstRow) firstRow = row;
        if (row >= firstRow + rows) firstRow = row - rows + 1;
    }

    /**
     * The entry the gadget will hold once in-flight loads land. The server's answer to a load arrives a moment
     * later, so trusting it alone would misjudge a quick back-and-forth.
     */
    private UUID heldOrRequested() {
        return lastRequested != null ? lastRequested : ClientEvents.heldEntry();
    }

    private void flushLoad() {
        if (queuedLoad != null) {
            PacketDistributor.sendToServer(new ClipboardActionPayload(Action.LOAD, queuedLoad));
            lastRequested = queuedLoad;
        }
        queuedLoad = null;
    }

    private void keep() {
        flushLoad();
        onClose();
    }

    private void cancel() {
        queuedLoad = null;
        if (originalCaptured && originalEntry != null && !originalEntry.equals(heldOrRequested()))
            PacketDistributor.sendToServer(new ClipboardActionPayload(Action.LOAD, originalEntry));
        onClose();
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= entries().size()) return;
        UUID id = entries().get(selected).id();
        if (id.equals(queuedLoad)) queuedLoad = null;
        if (id.equals(originalEntry)) originalEntry = null;
        PacketDistributor.sendToServer(new ClipboardActionPayload(Action.DELETE, id));
    }

    private void clearAll() {
        if (!confirmClear) {
            confirmClear = true;
            clearButton.setMessage(Component.translatable("bg2ergonomics.screen.confirmclear"));
            return;
        }
        queuedLoad = null;
        originalEntry = null;
        PacketDistributor.sendToServer(new ClipboardActionPayload(Action.CLEAR, ClipboardActionPayload.NONE));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int number = keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 ? keyCode - GLFW.GLFW_KEY_1
                : keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9 ? keyCode - GLFW.GLFW_KEY_KP_1 : -1;
        if (number >= 0) { //The number shown on the picture: keep it straight away
            if (number < entries().size()) {
                select(number, true);
                keep();
            }
            return true;
        }
        int page = rows * COLUMNS;
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> cancel();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> keep();
            case GLFW.GLFW_KEY_LEFT -> select(selected - 1, true);
            case GLFW.GLFW_KEY_RIGHT -> select(selected + 1, true);
            case GLFW.GLFW_KEY_UP -> select(selected - COLUMNS, true);
            case GLFW.GLFW_KEY_DOWN -> select(selected + COLUMNS, true);
            case GLFW.GLFW_KEY_PAGE_UP -> select(selected - page, true);
            case GLFW.GLFW_KEY_PAGE_DOWN -> select(selected + page, true);
            case GLFW.GLFW_KEY_HOME -> select(0, true);
            case GLFW.GLFW_KEY_END -> select(entries().size() - 1, true);
            case GLFW.GLFW_KEY_DELETE -> deleteSelected();
            default -> {
                if (ClientEvents.CLIPBOARD_KEY.matches(keyCode, scanCode)) { //Same key again keeps the selection, like a toggle
                    ClientEvents.clipboardKeyUsed(); //So letting go of it does not reopen the list
                    keep();
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        select(selected + (scrollY > 0 ? -1 : 1), true); //Wheel up moves toward newer, like the grid order
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tile = tileAt(mouseX, mouseY);
        if (button == 0 && tile >= 0) {
            select(tile, true);
            keep();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int tileAt(double mouseX, double mouseY) {
        if (mouseX < left() || mouseX >= left() + PANEL_WIDTH || mouseY < gridTop() || mouseY >= gridBottom()) return -1;
        int index = (firstRow + (int) ((mouseY - gridTop()) / CELL_H)) * COLUMNS + (int) ((mouseX - left()) / CELL_W);
        return index < entries().size() ? index : -1;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        //No full-screen dim: the paste preview in the world is the point of this screen
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        int left = left(), top = gridTop(), bottom = gridBottom(), hintTop = bottom + 40;
        List<FormattedCharSequence> hint = font.split(Component.translatable("bg2ergonomics.screen.clipboardhint"), PANEL_WIDTH);
        g.fill(left - 6, top - 22, left + PANEL_WIDTH + 6, hintTop + hint.size() * font.lineHeight + 6, 0xC0101010);
        g.drawCenteredString(font, title, width / 2, top - 16, 0xFFFFFF);

        List<ClipboardEntry> entries = entries();
        if (entries.isEmpty())
            g.drawCenteredString(font, Component.translatable("bg2ergonomics.screen.clipboardempty"), width / 2, (top + bottom) / 2 - 4, 0xAAAAAA);

        UUID held = ClientEvents.heldEntry();
        int hovered = tileAt(mouseX, mouseY);
        for (int i = firstRow * COLUMNS; i < Math.min(entries.size(), (firstRow + rows) * COLUMNS); i++) {
            ClipboardEntry entry = entries.get(i);
            int x = left + (i % COLUMNS) * CELL_W + (CELL_W - TILE) / 2, y = top + (i / COLUMNS - firstRow) * CELL_H;
            boolean isHeld = entry.id().equals(held);
            g.fill(x, y, x + TILE, y + TILE, i == selected ? 0x60FFFFFF : i == hovered ? 0x40FFFFFF : 0x40000000);
            ResourceLocation picture = ClipboardThumbnails.texture(entry.id());
            if (picture != null) {
                RenderSystem.enableBlend();
                g.blit(picture, x + 1, y + 1, TILE - 2, TILE - 2, 0, 0, ClipboardThumbnails.SIZE, ClipboardThumbnails.SIZE,
                        ClipboardThumbnails.SIZE, ClipboardThumbnails.SIZE);
                RenderSystem.disableBlend();
            } else {
                g.renderItem(iconFor(entry), x + TILE / 2 - 8, y + TILE / 2 - 8); //Until its picture is drawn
            }
            if (i == selected) g.renderOutline(x - 1, y - 1, TILE + 2, TILE + 2, 0xFFFFFFFF);
            else if (isHeld) g.renderOutline(x - 1, y - 1, TILE + 2, TILE + 2, 0xFF55FF55);
            if (i < 9) g.drawString(font, String.valueOf(i + 1), x + 3, y + 3, 0xFFFFFF, true);
            BlockPos s = entry.size();
            g.drawCenteredString(font, s.getX() + "x" + s.getY() + "x" + s.getZ(), x + TILE / 2, y + TILE + 2, isHeld ? 0x55FF55 : 0xBBBBBB);
        }

        int totalRows = (entries.size() + COLUMNS - 1) / COLUMNS;
        if (totalRows > rows) { //Scrollbar
            int gridHeight = bottom - top, barHeight = Math.max(8, gridHeight * rows / totalRows);
            int barY = top + (gridHeight - barHeight) * firstRow / (totalRows - rows);
            g.fill(left + PANEL_WIDTH + 2, barY, left + PANEL_WIDTH + 4, barY + barHeight, 0xFFAAAAAA);
        }

        if (selected >= 0 && selected < entries.size()) { //The selected copy in words
            ClipboardEntry entry = entries.get(selected);
            BlockPos s = entry.size();
            g.drawCenteredString(font, Component.translatable("bg2ergonomics.screen.clipboardinfo", selected + 1, s.getX(), s.getY(), s.getZ(),
                    String.format("%,d", entry.blockCount()), age(entry.timestamp())), width / 2, bottom + 2, 0xDDDDDD);
        }
        for (int i = 0; i < hint.size(); i++)
            g.drawCenteredString(font, hint.get(i), width / 2, hintTop + i * font.lineHeight, 0x888888);
        super.render(g, mouseX, mouseY, partialTicks);
    }

    private static ItemStack iconFor(ClipboardEntry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.iconBlock());
        ItemStack stack = id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.BLOCK.get(id).asItem());
        return stack.isEmpty() ? new ItemStack(Items.PAPER) : stack; //Blocks without an item, e.g. water
    }

    private static Component age(long timestamp) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestamp) / 1000);
        String amount = seconds < 60 ? seconds + "s" : seconds < 3600 ? seconds / 60 + "m" : seconds < 86400 ? seconds / 3600 + "h" : seconds / 86400 + "d";
        return Component.translatable("bg2ergonomics.screen.ago", amount);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
