package io.github.demux42.bg2ergonomics.clipboard;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.CopyLimits;
import io.github.demux42.bg2ergonomics.network.ClipboardActionPayload;
import io.github.demux42.bg2ergonomics.network.ClipboardPreviewPayload;
import io.github.demux42.bg2ergonomics.network.ClipboardSyncPayload;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.direwolf20.buildinggadgets2.util.modes.Paste;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Server side of the Copy/Paste gadget's clipboard history.
 * <p>
 * A copy is saved when the player starts using it: the gadget goes into paste mode, or the player opens or loads
 * from the clipboard. Copy-mode edits (corner clicks, BG2's coordinate screen) only change the live copy, so
 * adjusting a selection does not fill the history with intermediate boxes. BG2 has no event for a mode switch, so
 * the held gadget is checked each tick. The Cut/Paste gadget is left out on purpose: its data is a move, and
 * replaying it from history would duplicate blocks.
 */
public class ClipboardHistory {
    private static final Map<UUID, UUID> gadgetInPasteMode = new HashMap<>(); //Player -> gadget seen in paste mode last tick (server thread only)
    private static final Map<UUID, int[]> previewBudget = new HashMap<>(); //Player -> previews sent this second (server thread only)
    private static final int PREVIEWS_PER_SECOND = 20;

    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers())
            checkHeldGadget(player);
    }

    /**
     * Saves the held gadget's copy the first tick it is seen in paste mode. Rotating in paste mode is not a new
     * switch, so it does not add an entry per rotation.
     */
    public static void checkHeldGadget(ServerPlayer player) {
        ItemStack gadget = BaseGadget.getGadget(player);
        if (!(gadget.getItem() instanceof GadgetCopyPaste) || !(GadgetNBT.getMode(gadget) instanceof Paste)) {
            gadgetInPasteMode.remove(player.getUUID());
            return;
        }
        UUID gadgetId = GadgetNBT.getUUID(gadget);
        if (!gadgetId.equals(gadgetInPasteMode.put(player.getUUID(), gadgetId)))
            savePending(player, gadget);
    }

    public static boolean isPending(ClipboardData data, ItemStack gadget) {
        if (!GadgetNBT.hasCopyUUID(gadget)) return false;
        ClipboardData.Held held = data.held(GadgetNBT.getUUID(gadget));
        return held == null || !held.copyUUID().equals(GadgetNBT.getCopyUUID(gadget));
    }

    /**
     * The entry the gadget holds, or null when it holds nothing or an unsaved copy.
     */
    public static UUID heldEntry(ClipboardData data, ItemStack gadget) {
        if (!(gadget.getItem() instanceof GadgetCopyPaste) || isPending(data, gadget)) return null;
        ClipboardData.Held held = data.held(GadgetNBT.getUUID(gadget));
        return held == null ? null : held.entryId();
    }

    /**
     * Saves the gadget's copy as the newest entry unless it is already in the history.
     */
    public static void savePending(ServerPlayer player, ItemStack gadget) {
        int maxEntries = BG2Ergonomics.CLIPBOARD_SIZE.get();
        if (maxEntries <= 0 || !(gadget.getItem() instanceof GadgetCopyPaste)) return;
        ClipboardData data = ClipboardData.get(player.server);
        if (!isPending(data, gadget)) return;
        ArrayList<StatePos> list = BG2Data.get(player.server.overworld()).getCopyPasteList(GadgetNBT.getUUID(gadget), false);
        if (list == null || list.isEmpty() || list.size() > CopyLimits.copyBlocks()) return;
        ClipboardEntry entry = ClipboardEntry.of(UUID.randomUUID(), list);
        if (entry.blockCount() == 0) return; //An all-air copy is not worth a slot

        data.add(player.getUUID(), entry, BG2Data.statePosListToNBTMapArray(list), maxEntries);
        data.setHeld(GadgetNBT.getUUID(gadget), new ClipboardData.Held(entry.id(), GadgetNBT.getCopyUUID(gadget)));
        player.displayClientMessage(Component.translatable("bg2ergonomics.message.saved", entry.blockCount()), true);
        sync(player);
    }

    /**
     * Puts one of the player's own entries into the gadget and switches it to paste mode. An unsaved copy in the
     * gadget is saved first, so loading never loses work.
     */
    public static void load(ServerPlayer player, ItemStack gadget, UUID entryId) {
        if (!(gadget.getItem() instanceof GadgetCopyPaste)) return;
        ClipboardData data = ClipboardData.get(player.server);
        int found = ClipboardEntry.indexOf(data.history(player.getUUID()), entryId);
        if (found < 0) return; //Not this player's entry
        BlockPos size = data.history(player.getUUID()).get(found).size();
        if ((long) size.getX() * size.getY() * size.getZ() > CopyLimits.copyBlocks()) {
            //Saved under a higher limit (Building Gadgets Extra, since removed or lowered): BG2 could not send it to the client
            player.displayClientMessage(Component.translatable("bg2ergonomics.message.entrytoolarge", CopyLimits.copyBlocks()), true);
            return;
        }
        CompoundTag entryBlocks = data.blocks(entryId);
        if (entryBlocks == null) return;

        savePending(player, gadget);
        List<ClipboardEntry> history = data.history(player.getUUID());
        int index = ClipboardEntry.indexOf(history, entryId);
        if (index < 0) { //Saving the unsaved copy pushed this entry off a full history: put it back on top
            ClipboardEntry restored = ClipboardEntry.of(entryId, BG2Data.statePosListFromNBTMapArray(entryBlocks));
            data.add(player.getUUID(), restored, entryBlocks, Math.max(1, BG2Ergonomics.CLIPBOARD_SIZE.get()));
            history = data.history(player.getUUID());
            index = 0;
        }

        UUID gadgetId = GadgetNBT.getUUID(gadget);
        BG2Data.get(player.server.overworld()).addToCopyPaste(gadgetId, BG2Data.statePosListFromNBTMapArray(entryBlocks));
        GadgetNBT.setCopyUUID(gadget); //A new copy UUID makes the client fetch the new blocks for BG2's paste preview
        GadgetNBT.setMode(gadget, new Paste());
        data.setHeld(gadgetId, new ClipboardData.Held(entryId, GadgetNBT.getCopyUUID(gadget)));
        gadgetInPasteMode.put(player.getUUID(), gadgetId);
        player.displayClientMessage(Component.translatable("bg2ergonomics.message.loaded",
                index + 1, history.size(), history.get(index).blockCount()), true);
        sync(player);
    }

    public static void delete(ServerPlayer player, UUID entryId) {
        if (ClipboardData.get(player.server).remove(player.getUUID(), entryId))
            sync(player);
    }

    public static void clear(ServerPlayer player) {
        ClipboardData.get(player.server).clear(player.getUUID());
        sync(player);
    }

    /**
     * Sends what one of the player's own entries' thumbnail is drawn from. The reduction runs off the server
     * thread (a big copy takes a moment), and each player gets {@link #PREVIEWS_PER_SECOND}; the client asks again
     * for any it did not get.
     */
    public static void sendPreview(ServerPlayer player, UUID entryId) {
        if (player.isFakePlayer() || !player.connection.hasChannel(ClipboardPreviewPayload.TYPE)) return;
        int now = player.server.getTickCount();
        int[] budget = previewBudget.computeIfAbsent(player.getUUID(), k -> new int[]{now, 0}); //{window start tick, sent}
        if (now - budget[0] >= 20) {
            budget[0] = now;
            budget[1] = 0;
        }
        if (++budget[1] > PREVIEWS_PER_SECOND) return;
        ClipboardData data = ClipboardData.get(player.server);
        if (ClipboardEntry.indexOf(data.history(player.getUUID()), entryId) < 0) return; //Not this player's entry
        CompoundTag blocks = data.blocks(entryId); //Never changed once saved, so it can be read off the server thread
        if (blocks == null) return;
        CompletableFuture.supplyAsync(() -> ClipboardPreview.of(BG2Data.statePosListFromNBTMapArray(blocks)), Util.backgroundExecutor())
                .thenAcceptAsync(preview -> {
                    if (!player.hasDisconnected()) player.connection.send(new ClipboardPreviewPayload(entryId, preview));
                }, player.server)
                .exceptionally(error -> {
                    BG2Ergonomics.LOGGER.warn("Could not make a clipboard preview", error);
                    return null;
                });
    }

    public static void sync(ServerPlayer player) {
        if (player.isFakePlayer() || !player.connection.hasChannel(ClipboardSyncPayload.TYPE))
            return; //NeoForge refuses a payload the client never negotiated, e.g. a client without this mod
        ClipboardData data = ClipboardData.get(player.server);
        UUID held = heldEntry(data, BaseGadget.getGadget(player));
        player.connection.send(new ClipboardSyncPayload(new ArrayList<>(data.history(player.getUUID())),
                held == null ? ClipboardActionPayload.NONE : held));
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            sync(player);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        gadgetInPasteMode.remove(event.getEntity().getUUID());
        previewBudget.remove(event.getEntity().getUUID());
    }
}
