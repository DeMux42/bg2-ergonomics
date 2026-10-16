package io.github.demux42.bg2ergonomics;

import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * The copy size limits actually in force. BG2 hard-codes them (Copy.collectWorld, GadgetCutPaste). Building Gadgets
 * Extra raises the Copy/Paste block count with its maxCopyVolume server setting, which NeoForge syncs to clients, and
 * leaves the per-axis and Cut/Paste limits alone.
 */
public final class CopyLimits {
    public static final int AXIS = 500;          //Corner distance per axis, Copy and Cut
    public static final int BG2_BLOCKS = 100000; //Blocks in the box, Copy and Cut

    private static final String EXTRA = "buildinggadgetsextra";
    private static Method extraVolume;

    private CopyLimits() {
    }

    /**
     * Most blocks one Copy/Paste copy may hold on this server.
     */
    public static int copyBlocks() {
        if (!ModList.get().isLoaded(EXTRA)) return BG2_BLOCKS;
        try {
            if (extraVolume == null)
                extraVolume = Class.forName("com.nstut.buildinggadgetsextra.setup.ExtraConfig").getMethod("maxCopyVolume");
            return (int) extraVolume.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return BG2_BLOCKS; //Its config is not loaded yet, or a newer Extra moved it: BG2's own limit never over-reaches
        }
    }
}
