package io.github.demux42.bg2ergonomics.clipboard;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * The add-on's own world data (data/bg2ergonomics_clipboard.dat). Blocks are kept in BG2's own compact NBT
 * format (BG2Data.statePosListToNBTMapArray), so nothing is decoded until an entry is loaded into a gadget.
 */
public class ClipboardData extends SavedData {
    private static final String NAME = "bg2ergonomics_clipboard";

    /**
     * Which entry a gadget holds, and the gadget's copy UUID at the time. Any change to the gadget's copy gives it
     * a new copy UUID, which is how an unsaved copy is spotted.
     */
    public record Held(UUID entryId, UUID copyUUID) {
    }

    private final Map<UUID, ArrayList<ClipboardEntry>> histories = new HashMap<>(); //Player -> history, newest first
    private final Map<UUID, CompoundTag> blocks = new HashMap<>(); //Entry id -> blocks
    private final Map<UUID, Held> held = new HashMap<>(); //Gadget UUID -> what it holds

    public static ClipboardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ClipboardData::new, ClipboardData::load), NAME);
    }

    public List<ClipboardEntry> history(UUID player) {
        return Collections.unmodifiableList(histories.getOrDefault(player, new ArrayList<>()));
    }

    public CompoundTag blocks(UUID entryId) {
        return blocks.get(entryId);
    }

    /**
     * Puts an entry at the head of the player's history and deletes whatever falls off the end.
     */
    public void add(UUID player, ClipboardEntry entry, CompoundTag entryBlocks, int maxEntries) {
        ArrayList<ClipboardEntry> history = histories.computeIfAbsent(player, k -> new ArrayList<>());
        history.add(0, entry);
        blocks.put(entry.id(), entryBlocks);
        while (history.size() > maxEntries)
            blocks.remove(history.remove(history.size() - 1).id());
        setDirty();
    }

    public boolean remove(UUID player, UUID entryId) {
        ArrayList<ClipboardEntry> history = histories.get(player);
        if (history == null || !history.removeIf(e -> e.id().equals(entryId))) return false;
        blocks.remove(entryId);
        setDirty();
        return true;
    }

    public void clear(UUID player) {
        ArrayList<ClipboardEntry> history = histories.remove(player);
        if (history == null) return;
        history.forEach(e -> blocks.remove(e.id()));
        setDirty();
    }

    public Held held(UUID gadget) {
        return held.get(gadget);
    }

    public void setHeld(UUID gadget, Held value) {
        held.put(gadget, value);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag historyList = new ListTag();
        histories.forEach((player, history) -> {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", player);
            ListTag entries = new ListTag();
            for (ClipboardEntry entry : history) {
                CompoundTag entryTag = entry.toTag();
                entryTag.put("blocks", blocks.get(entry.id()));
                entries.add(entryTag);
            }
            playerTag.put("entries", entries);
            historyList.add(playerTag);
        });
        tag.put("histories", historyList);

        ListTag heldList = new ListTag();
        held.forEach((gadget, h) -> {
            CompoundTag heldTag = new CompoundTag();
            heldTag.putUUID("gadget", gadget);
            heldTag.putUUID("entry", h.entryId());
            heldTag.putUUID("copy", h.copyUUID());
            heldList.add(heldTag);
        });
        tag.put("held", heldList);
        return tag;
    }

    public static ClipboardData load(CompoundTag tag, HolderLookup.Provider registries) {
        ClipboardData data = new ClipboardData();
        ListTag historyList = tag.getList("histories", Tag.TAG_COMPOUND);
        for (int i = 0; i < historyList.size(); i++) {
            CompoundTag playerTag = historyList.getCompound(i);
            ListTag entries = playerTag.getList("entries", Tag.TAG_COMPOUND);
            ArrayList<ClipboardEntry> history = new ArrayList<>();
            for (int j = 0; j < entries.size(); j++) {
                CompoundTag entryTag = entries.getCompound(j);
                ClipboardEntry entry = ClipboardEntry.fromTag(entryTag);
                history.add(entry);
                data.blocks.put(entry.id(), entryTag.getCompound("blocks"));
            }
            data.histories.put(playerTag.getUUID("player"), history);
        }
        ListTag heldList = tag.getList("held", Tag.TAG_COMPOUND);
        for (int i = 0; i < heldList.size(); i++) {
            CompoundTag heldTag = heldList.getCompound(i);
            data.held.put(heldTag.getUUID("gadget"), new Held(heldTag.getUUID("entry"), heldTag.getUUID("copy")));
        }
        return data;
    }
}
