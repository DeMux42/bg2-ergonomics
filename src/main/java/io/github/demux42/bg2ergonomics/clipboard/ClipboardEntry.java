package io.github.demux42.bg2ergonomics.clipboard;

import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One copy in a player's clipboard history: what the list shows. The blocks are stored separately in
 * {@link ClipboardData} under {@link #id()}.
 *
 * @param size      bounding box dimensions (x, y, z)
 * @param iconBlock registry name of the most common non-air block, used as the row icon
 */
public record ClipboardEntry(UUID id, long timestamp, int blockCount, BlockPos size, String iconBlock) {
    public static final StreamCodec<FriendlyByteBuf, ClipboardEntry> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, ClipboardEntry::id,
            ByteBufCodecs.VAR_LONG, ClipboardEntry::timestamp,
            ByteBufCodecs.VAR_INT, ClipboardEntry::blockCount,
            BlockPos.STREAM_CODEC, ClipboardEntry::size,
            ByteBufCodecs.STRING_UTF8, ClipboardEntry::iconBlock,
            ClipboardEntry::new
    );

    /**
     * Summarises a copy for display. An empty or all-air list gives a block count of 0.
     */
    public static ClipboardEntry of(UUID id, List<StatePos> list) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Block, Integer> counts = new HashMap<>();
        int blocks = 0;
        for (StatePos statePos : list) {
            BlockPos p = statePos.pos;
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
            if (statePos.state.isAir()) continue;
            blocks++;
            counts.merge(statePos.state.getBlock(), 1, Integer::sum);
        }
        BlockPos size = list.isEmpty() ? BlockPos.ZERO : new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        String icon = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> BuiltInRegistries.BLOCK.getKey(e.getKey()).toString())
                .orElse("minecraft:air");
        return new ClipboardEntry(id, System.currentTimeMillis(), blocks, size, icon);
    }

    public static int indexOf(List<ClipboardEntry> history, UUID entryId) {
        for (int i = 0; i < history.size(); i++)
            if (history.get(i).id().equals(entryId)) return i;
        return -1;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putLong("timestamp", timestamp);
        tag.putInt("blockcount", blockCount);
        tag.putLong("size", size.asLong());
        tag.putString("icon", iconBlock);
        return tag;
    }

    public static ClipboardEntry fromTag(CompoundTag tag) {
        return new ClipboardEntry(tag.getUUID("id"), tag.getLong("timestamp"), tag.getInt("blockcount"),
                BlockPos.of(tag.getLong("size")), tag.getString("icon"));
    }
}
