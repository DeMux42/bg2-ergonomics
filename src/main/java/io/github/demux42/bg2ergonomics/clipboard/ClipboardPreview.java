package io.github.demux42.bg2ergonomics.clipboard;

import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * What a clipboard thumbnail is drawn from: a cut-down copy, small enough to send for every entry. Only blocks
 * that can be seen from outside are kept, and a copy with more of those than {@link #MAX_CUBES} is thinned into
 * {@code scale}-sized cubes, each showing the top block of its cell. A thumbnail is about 128 pixels across, so a
 * big copy loses nothing visible.
 *
 * @param scale how many blocks each cube stands for along each side
 * @param cubes positions in cubes from the copy's low corner
 */
public record ClipboardPreview(int scale, List<Cube> cubes) {
    public static final int MAX_CUBES = 4096; //~25 KB on the wire, and quick to draw

    public record Cube(int x, int y, int z, BlockState state) {
    }

    public static ClipboardPreview of(List<StatePos> blocks) {
        Long2ObjectOpenHashMap<BlockState> solid = new Long2ObjectOpenHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (StatePos block : blocks) {
            if (block.state.isAir()) continue;
            solid.put(block.pos.asLong(), block.state);
            minX = Math.min(minX, block.pos.getX());
            minY = Math.min(minY, block.pos.getY());
            minZ = Math.min(minZ, block.pos.getZ());
        }
        List<StatePos> shell = new ArrayList<>();
        for (StatePos block : blocks)
            if (!block.state.isAir() && seen(solid, block.pos)) shell.add(block);

        for (int scale = 1; ; scale++) {
            Long2ObjectLinkedOpenHashMap<Cube> cells = new Long2ObjectLinkedOpenHashMap<>();
            Long2IntOpenHashMap topY = new Long2IntOpenHashMap(); //The highest block seen in each cell: tops read best from above
            for (StatePos block : shell) {
                int x = (block.pos.getX() - minX) / scale, y = (block.pos.getY() - minY) / scale, z = (block.pos.getZ() - minZ) / scale;
                long key = BlockPos.asLong(x, y, z);
                if (!cells.containsKey(key) || block.pos.getY() > topY.get(key)) {
                    cells.put(key, new Cube(x, y, z, block.state));
                    topY.put(key, block.pos.getY());
                }
            }
            if (cells.size() <= MAX_CUBES) return new ClipboardPreview(scale, List.copyOf(cells.values()));
        }
    }

    /**
     * Whether any of the six neighbours lets the block be seen: empty, outside the copy, or not a full opaque cube.
     */
    private static boolean seen(Long2ObjectOpenHashMap<BlockState> solid, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockState next = solid.get(pos.relative(side).asLong());
            if (next == null || !next.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return true;
        }
        return false;
    }

    public static final StreamCodec<FriendlyByteBuf, ClipboardPreview> STREAM_CODEC = StreamCodec.of(ClipboardPreview::write, ClipboardPreview::read);

    private static void write(FriendlyByteBuf buf, ClipboardPreview preview) {
        Object2IntOpenHashMap<BlockState> index = new Object2IntOpenHashMap<>();
        List<BlockState> palette = new ArrayList<>();
        for (Cube cube : preview.cubes())
            if (!index.containsKey(cube.state())) {
                index.put(cube.state(), palette.size());
                palette.add(cube.state());
            }
        buf.writeVarInt(preview.scale());
        buf.writeVarInt(palette.size());
        for (BlockState state : palette) buf.writeVarInt(Block.getId(state));
        buf.writeVarInt(preview.cubes().size());
        for (Cube cube : preview.cubes()) {
            buf.writeVarInt(cube.x());
            buf.writeVarInt(cube.y());
            buf.writeVarInt(cube.z());
            buf.writeVarInt(index.getInt(cube.state()));
        }
    }

    /**
     * Counts are checked before anything is allocated: this is data from the server.
     */
    private static ClipboardPreview read(FriendlyByteBuf buf) {
        int scale = Math.max(1, buf.readVarInt());
        int paletteSize = buf.readVarInt();
        if (paletteSize < 0 || paletteSize > MAX_CUBES) throw new DecoderException("Clipboard preview palette of " + paletteSize);
        List<BlockState> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) palette.add(Block.stateById(buf.readVarInt())); //An unknown id reads as air
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CUBES) throw new DecoderException("Clipboard preview of " + count + " cubes");
        List<Cube> cubes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt(), state = buf.readVarInt();
            if (state < 0 || state >= paletteSize) throw new DecoderException("Clipboard preview palette index " + state);
            cubes.add(new Cube(x, y, z, palette.get(state)));
        }
        return new ClipboardPreview(scale, List.copyOf(cubes));
    }
}
