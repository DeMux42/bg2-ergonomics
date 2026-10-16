package io.github.demux42.bg2ergonomics.controls;

import net.minecraft.world.phys.AABB;

/**
 * How the selection outline follows the wheel, like a keyboard switch bottoming out: it covers most of the step
 * in the first frame or two, then settles onto the block without overshooting or bouncing, so where it lands is
 * never in doubt. A new step mid-glide starts from wherever the outline is, so a fast wheel streams smoothly.
 * The moved face also flashes on each step and fades, the visual "thock".
 */
public final class Glide {
    static final double SETTLE_MS = 18; //Time constant: 60% of the way after one 60 fps frame, 95% after ~55 ms
    public static final double DONE_MS = 120; //From here on it sits exactly on the target
    static final double PULSE_MS = 80; //How fast the flash fades

    private Glide() {
    }

    /**
     * The outline {@code ms} milliseconds after it started moving from {@code from} toward {@code to}.
     */
    public static AABB at(AABB from, AABB to, double ms) {
        if (ms >= DONE_MS) return to;
        double left = Math.exp(-Math.max(ms, 0) / SETTLE_MS);
        return new AABB(to.minX + (from.minX - to.minX) * left, to.minY + (from.minY - to.minY) * left,
                to.minZ + (from.minZ - to.minZ) * left, to.maxX + (from.maxX - to.maxX) * left,
                to.maxY + (from.maxY - to.maxY) * left, to.maxZ + (from.maxZ - to.maxZ) * left);
    }

    /**
     * Flash strength {@code ms} after a step: 1 on impact, fading to 0.
     */
    public static double pulse(double ms) {
        return ms < 0 ? 0 : Math.exp(-ms / PULSE_MS);
    }
}
