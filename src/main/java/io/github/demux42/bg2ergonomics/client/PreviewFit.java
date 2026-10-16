package io.github.demux42.bg2ergonomics.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where the Template Manager's preview camera sits so the whole template shows, turning around its centre. No
 * client-only classes, so the server game test can check it.
 * <p>
 * BG2 centred on (lengthZ, lengthY) instead of (lengthX, lengthY), rotated around a corner and backed off by the Z
 * length alone, so wide or long templates sat off-centre or out of view.
 *
 * @param centre   centre of the template's blocks, outer edges included
 * @param radius   radius of the sphere around the template's box
 * @param distance how far back the camera sits so that sphere fills the view's height
 * @param step     blocks per unit of BG2's zoom and pan, so the wheel and drag still cover a big template
 */
public record PreviewFit(Vec3 centre, float radius, float distance, float step) {
    /**
     * @param corner1 one corner of the template, as BG2 lists it (its first block)
     * @param corner2 the opposite corner (its last block)
     * @param fov     vertical field of view in degrees
     */
    public static PreviewFit of(BlockPos corner1, BlockPos corner2, float fov) {
        double sx = Math.abs(corner1.getX() - corner2.getX()) + 1, sy = Math.abs(corner1.getY() - corner2.getY()) + 1, sz = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        Vec3 centre = new Vec3(Math.min(corner1.getX(), corner2.getX()) + sx / 2, Math.min(corner1.getY(), corner2.getY()) + sy / 2,
                Math.min(corner1.getZ(), corner2.getZ()) + sz / 2);
        float radius = (float) (Math.sqrt(sx * sx + sy * sy + sz * sz) / 2);
        float distance = (float) (radius / Math.sin(Math.toRadians(fov) / 2));
        return new PreviewFit(centre, radius, distance, Math.max(1, radius / 8));
    }
}
