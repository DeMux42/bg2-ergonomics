package io.github.demux42.bg2ergonomics.controls;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Which side of a box the player means, read like a radial menu laid over the side they point at, so it goes by
 * where the crosshair is on screen rather than where the player stands:
 * <ul>
 *     <li>The middle half of a side picks that side.</li>
 *     <li>Its outer quarter picks the side beyond that edge when that side is hard to point at (out of sight, or
 *     seen edge-on): the left of the front picks the left side, through the front. The same goes for top, bottom
 *     and right, and the far edge of the top picks the back.</li>
 *     <li>Just past the box picks the side behind that edge, the same way.</li>
 *     <li>Sides seen squarely are never redirected to, since they can be pointed at directly; that keeps the pick
 *     from flipping back and forth along the edge between two of them.</li>
 * </ul>
 * From inside the box: the side looked at.
 */
public final class Aim {
    static final double CENTRE = 0.5; //The middle half of a side picks that side...
    static final double MIN_CENTRE_BLOCKS = 0.5; //...and at least one block of it, so a 1-thick side is not all edge
    static final double REACH = 1.5; //Blocks past the box that still count as pointing at it
    static final double SQUARE = 0.35; //A side seen within ~70 degrees of straight on can be pointed at directly

    private Aim() {
    }

    public static Direction face(Vec3 eye, Vec3 look, AABB box) {
        Vec3 far = eye.add(look.scale(1024));
        if (box.contains(eye)) {
            BlockHitResult exit = AABB.clip(List.of(box), far, eye, BlockPos.ZERO);
            return exit == null ? null : exit.getDirection();
        }
        BlockHitResult hit = AABB.clip(List.of(box), eye, far, BlockPos.ZERO);
        if (hit == null) hit = AABB.clip(List.of(box.inflate(REACH)), eye, far, BlockPos.ZERO);
        if (hit == null) return null;
        Direction face = hit.getDirection();
        Vec3 centre = box.getCenter(), offset = hit.getLocation().subtract(centre);
        Direction beyond = null;
        double furthest = 0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == face.getAxis()) continue;
            double half = (box.max(axis) - box.min(axis)) / 2, along = offset.get(axis);
            double into = (Math.abs(along) - Math.max(CENTRE * half, Math.min(half, MIN_CENTRE_BLOCKS))) / half; //How far into the edge band
            Direction side = Direction.fromAxisAndDirection(axis, along > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            if (into > furthest && !seenSquarely(eye, box, side)) {
                furthest = into;
                beyond = side;
            }
        }
        return beyond != null ? beyond : face;
    }

    private static boolean seenSquarely(Vec3 eye, AABB box, Direction side) {
        Vec3 centre = box.getCenter(), normal = Vec3.atLowerCornerOf(side.getNormal());
        Direction.Axis axis = side.getAxis();
        Vec3 faceCentre = centre.add(normal.scale((box.max(axis) - box.min(axis)) / 2));
        Vec3 toEye = eye.subtract(faceCentre);
        return normal.dot(toEye) > SQUARE * toEye.length();
    }
}
