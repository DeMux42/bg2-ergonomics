package io.github.demux42.bg2ergonomics.mixin;

import com.direwolf20.buildinggadgets2.util.GadgetUtils;
import com.direwolf20.buildinggadgets2.util.modes.Copy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Copies keep doors, trapdoors and fence gates open. BG2 resets every block's "open" state when copying
 * (GadgetUtils.cleanBlockState), so an open trapdoor pasted back closed (BG2 issue #175). Only Copy is changed: the
 * same cleaning picks the Building gadget's block, and Cut/Paste keeps full states already. Server side; without
 * this add-on on the server, copies behave as in plain BG2.
 */
@Mixin(Copy.class)
public abstract class CopyMixin {
    //The per-block lambda inside Copy.collectWorld, where BG2 cleans each copied state
    @Redirect(method = "lambda$collectWorld$0", at = @At(value = "INVOKE",
            target = "Lcom/direwolf20/buildinggadgets2/util/GadgetUtils;cleanBlockState(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState bg2ergonomics$keepOpen(BlockState source) {
        BlockState clean = GadgetUtils.cleanBlockState(source);
        Block block = source.getBlock();
        boolean keep = (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock)
                && source.hasProperty(BlockStateProperties.OPEN);
        return keep ? clean.setValue(BlockStateProperties.OPEN, source.getValue(BlockStateProperties.OPEN)) : clean;
    }
}
