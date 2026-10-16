package io.github.demux42.bg2ergonomics.controls;

import io.github.demux42.bg2ergonomics.CopyLimits;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * A Copy or Cut selection as BG2 stores it: two inclusive corners. Which corner is the start matters, because BG2
 * pastes relative to it, so edits keep each corner on its side of the box.
 */
public record Box(BlockPos start, BlockPos end) {
    /**
     * The held gadget's selection, or null while either corner is unset.
     */
    public static Box of(ItemStack gadget) {
        BlockPos start = GadgetNBT.getCopyStartPos(gadget), end = GadgetNBT.getCopyEndPos(gadget);
        return start.equals(GadgetNBT.nullPos) || end.equals(GadgetNBT.nullPos) ? null : new Box(start, end);
    }

    public int size(Direction.Axis axis) {
        return Math.abs(start.get(axis) - end.get(axis)) + 1;
    }

    public long blocks() {
        return (long) size(Direction.Axis.X) * size(Direction.Axis.Y) * size(Direction.Axis.Z);
    }

    /**
     * Whether BG2 accepts it: corner distance per axis, then blocks in the box, as BG2's own Copy/Cut checks do. A
     * refused selection makes BG2 store an empty copy, so never send one.
     */
    public boolean fitsBG2(int maxBlocks) {
        for (Direction.Axis axis : Direction.Axis.values())
            if (size(axis) - 1 > CopyLimits.AXIS) return false;
        return blocks() <= maxBlocks;
    }

    /**
     * Moves one face outward by {@code blocks} (negative pulls it in). Once the box is one block thick, pulling
     * further drags the opposite face along, so the whole box keeps moving the way the face was going.
     */
    public Box pushFace(Direction face, int blocks) {
        Direction.Axis axis = face.getAxis();
        int lo = Math.min(start.get(axis), end.get(axis)), hi = Math.max(start.get(axis), end.get(axis));
        if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            hi += blocks;
            lo = Math.min(lo, hi);
        } else {
            lo -= blocks;
            hi = Math.max(hi, lo);
        }
        boolean startIsLow = start.get(axis) <= end.get(axis);
        return new Box(with(start, axis, startIsLow ? lo : hi), with(end, axis, startIsLow ? hi : lo));
    }

    public Box move(Direction direction, int blocks) {
        return new Box(start.relative(direction, blocks), end.relative(direction, blocks));
    }

    /**
     * The world-space box the selection covers, outer edges included.
     */
    public AABB bounds() {
        return new AABB(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()),
                Math.max(start.getX(), end.getX()) + 1, Math.max(start.getY(), end.getY()) + 1, Math.max(start.getZ(), end.getZ()) + 1);
    }

    private static BlockPos with(BlockPos pos, Direction.Axis axis, int value) {
        return switch (axis) {
            case X -> new BlockPos(value, pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), value, pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), value);
        };
    }
}
