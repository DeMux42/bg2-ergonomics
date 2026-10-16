package io.github.demux42.bg2ergonomics.client;

import com.direwolf20.buildinggadgets2.util.FakeRenderingWorld;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * BG2's Template Manager rebuilt its preview world on every frame: a map of every block in the template, a
 * neighbour-shape update for each of them, a scan for chests and other block-entity blocks, and a fresh block
 * entity for each of those. On a big template that is what drops frames. None of it changes until the template
 * does, so it is built once per template here. BG2DataClient hands out a new list on every change, which makes the
 * list itself the cache key.
 */
public final class TemplatePreviewCache {
    private static ArrayList<StatePos> blocks;
    private static Level level;
    private static World world;
    private static List<StatePos> blockEntityBlocks;

    private TemplatePreviewCache() {
    }

    public static FakeRenderingWorld world(Level realLevel, ArrayList<StatePos> list, BlockPos origin) {
        if (list != blocks || realLevel != level || world == null) {
            blocks = list;
            level = realLevel;
            world = new World(realLevel, list, origin);
            blockEntityBlocks = null;
        }
        return world;
    }

    /**
     * BG2's list of blocks drawn by a block-entity renderer. The stream is lazy, so returning the cached list skips
     * the scan entirely.
     */
    public static List<StatePos> blockEntityBlocks(Stream<StatePos> filtered) {
        if (blockEntityBlocks == null) blockEntityBlocks = filtered.toList();
        return blockEntityBlocks;
    }

    public static void clear() {
        blocks = null;
        level = null;
        world = null;
        blockEntityBlocks = null;
    }

    /**
     * Keeps each block entity instead of making a new one per frame, as BG2's in-world preview already does, and
     * checks for a block that makes none, where BG2 crashes (BG2 issue #219).
     */
    private static final class World extends FakeRenderingWorld {
        private final Level realLevel;
        private final BlockPos origin;
        private Map<BlockPos, BlockEntity> blockEntities; //Null while FakeRenderingWorld's constructor runs, which already asks for some

        World(Level realLevel, ArrayList<StatePos> list, BlockPos origin) {
            super(realLevel, list, origin);
            this.realLevel = realLevel;
            this.origin = origin;
            blockEntities = new HashMap<>();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (blockEntities == null) return super.getBlockEntity(pos);
            return blockEntities.computeIfAbsent(pos.immutable(), this::newBlockEntity);
        }

        @Nullable
        private BlockEntity newBlockEntity(BlockPos pos) {
            BlockState state = getBlockState(pos);
            if (!(state.getBlock() instanceof EntityBlock block)) return null;
            BlockEntity blockEntity = block.newBlockEntity(pos.offset(origin), state);
            if (blockEntity != null) blockEntity.setLevel(realLevel);
            return blockEntity;
        }
    }
}
