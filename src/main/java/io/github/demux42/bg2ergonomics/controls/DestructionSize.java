package io.github.demux42.bg2ergonomics.controls;

import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.GadgetNBT.IntSettings;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Destruction gadget's five sizes, and which of them points where in the world.
 */
public record DestructionSize(int left, int right, int up, int down, int depth) {
    public static final int MAX = 16; //BG2's DestructionGUI sliders: 0-16 each, so left+right and up+down stay within its 32

    /**
     * Which size to change, and +1 when growing it moves toward the direction asked for, -1 when it moves away.
     */
    public record Side(IntSettings setting, int sign) {
    }

    public static DestructionSize of(ItemStack gadget) {
        return new DestructionSize(get(gadget, IntSettings.LEFT), get(gadget, IntSettings.RIGHT), get(gadget, IntSettings.UP),
                get(gadget, IntSettings.DOWN), get(gadget, IntSettings.DEPTH));
    }

    private static int get(ItemStack gadget, IntSettings setting) {
        return GadgetNBT.getToolValue(gadget, setting.getName());
    }

    public int get(IntSettings setting) {
        return switch (setting) {
            case LEFT -> left;
            case RIGHT -> right;
            case UP -> up;
            case DOWN -> down;
            case DEPTH -> depth;
            default -> 0;
        };
    }

    /**
     * Changes one size, kept within BG2's slider range. Depth stays at 1 or more, since BG2 destroys nothing at 0.
     */
    public DestructionSize adjust(IntSettings setting, int delta) {
        int value = Math.clamp((long) get(setting) + delta, setting == IntSettings.DEPTH ? 1 : 0, MAX);
        return new DestructionSize(setting == IntSettings.LEFT ? value : left, setting == IntSettings.RIGHT ? value : right,
                setting == IntSettings.UP ? value : up, setting == IntSettings.DOWN ? value : down, setting == IntSettings.DEPTH ? value : depth);
    }

    public String dimensions() {
        return (1 + left + right) + " x " + (1 + up + down) + " x " + depth;
    }

    /**
     * BG2's directions for a hit face, as GadgetUtils.getDestructionArea lays the area out: on a wall "up" is up, on
     * a floor or ceiling it is the way the player faces.
     */
    private static Direction up(Direction face, Direction facing) {
        return face.getAxis().isVertical() ? facing : Direction.UP;
    }

    private static Direction right(Direction face, Direction facing) {
        return face.getAxis().isVertical() ? facing.getClockWise() : face.getCounterClockWise();
    }

    /**
     * The size under the crosshair: the outer thirds of the hit face pick left, right, up or down, the middle third
     * picks depth. {@code offset} is the hit point minus the block's centre.
     */
    public static Side aimed(Direction face, Direction facing, Vec3 offset) {
        Direction up = up(face, facing), right = right(face, facing);
        double u = dot(offset, right), v = dot(offset, up);
        if (Math.max(Math.abs(u), Math.abs(v)) < 1 / 6d) return new Side(IntSettings.DEPTH, 1);
        if (Math.abs(u) >= Math.abs(v)) return new Side(u > 0 ? IntSettings.RIGHT : IntSettings.LEFT, 1);
        return new Side(v > 0 ? IntSettings.UP : IntSettings.DOWN, 1);
    }

    private static double dot(Vec3 v, Direction d) {
        return v.x * d.getStepX() + v.y * d.getStepY() + v.z * d.getStepZ();
    }
}
