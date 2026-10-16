package io.github.demux42.bg2ergonomics.controls;

import com.direwolf20.buildinggadgets2.common.events.ServerTickHandler;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.direwolf20.buildinggadgets2.util.datatypes.TagPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors the copy a Copy/Paste or Cut/Paste gadget holds, the server-side counterpart of BG2's Rotate button.
 * <p>
 * The copy is flipped in place, so the paste keeps its footprint, and the list keeps its order: BG2 takes the first
 * and last entries as opposite corners when it saves or sends a copy, and a flip keeps them opposite.
 */
public final class Mirroring {
    private Mirroring() {
    }

    /**
     * Flips the held gadget's copy across {@code axis} (Y turns it upside down) and gives it a new copy id, so the
     * client fetches the result for BG2's paste preview.
     */
    public static void mirrorGadget(ServerPlayer player, ItemStack gadget, Direction.Axis axis) {
        if (!(gadget.getItem() instanceof GadgetCopyPaste || gadget.getItem() instanceof GadgetCutPaste)) return;
        UUID id = GadgetNBT.getUUID(gadget);
        if (gadget.getItem() instanceof GadgetCutPaste && ServerTickHandler.gadgetWorking(id)) {
            player.displayClientMessage(Component.translatable("buildinggadgets2.messages.cutinprogress"), true);
            return; //Same guard as BG2's rotate: the cut is still being collected
        }
        BG2Data bg2 = BG2Data.get(player.server.overworld());
        ArrayList<StatePos> list = bg2.getCopyPasteList(id, false);
        if (list == null || list.isEmpty()) return;
        ArrayList<TagPos> tags = bg2.peekTEMap(id);
        bg2.addToCopyPaste(id, mirror(list, tags, axis));
        if (tags != null && !tags.isEmpty()) bg2.addToTEMap(id, tags);
        GadgetNBT.setCopyUUID(gadget);
        player.displayClientMessage(Component.translatable(axis == Direction.Axis.Y ? "bg2ergonomics.message.flipped" : "bg2ergonomics.message.mirrored"), true);
    }

    /**
     * The copy flipped in place across {@code axis}. Block-entity data in {@code tags} moves with its block.
     */
    public static ArrayList<StatePos> mirror(List<StatePos> list, @Nullable List<TagPos> tags, Direction.Axis axis) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (StatePos statePos : list) {
            lo = Math.min(lo, statePos.pos.get(axis));
            hi = Math.max(hi, statePos.pos.get(axis));
        }
        int sum = lo + hi; //A block at v lands at sum - v
        ArrayList<StatePos> mirrored = new ArrayList<>(list.size());
        for (StatePos statePos : list)
            mirrored.add(new StatePos(flip(statePos.state, axis), flip(statePos.pos, axis, sum)));
        if (tags != null)
            for (TagPos tag : tags)
                tag.pos = flip(tag.pos, axis, sum);
        return mirrored;
    }

    private static BlockPos flip(BlockPos pos, Direction.Axis axis, int sum) {
        return switch (axis) {
            case X -> new BlockPos(sum - pos.getX(), pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), sum - pos.getY(), pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), sum - pos.getZ());
        };
    }

    public static BlockState flip(BlockState state, Direction.Axis axis) {
        return switch (axis) {
            case X -> state.mirror(Mirror.FRONT_BACK); //Vanilla's FRONT_BACK flips east and west
            case Z -> state.mirror(Mirror.LEFT_RIGHT); //and LEFT_RIGHT flips north and south
            case Y -> upsideDown(state);
        };
    }

    /**
     * Vanilla has no vertical mirror, so this flips the properties that mean up or down: stair and trapdoor halves,
     * slabs, button and bell attachment, up/down facings, up/down connections and lanterns. A value the block does
     * not allow (a hopper cannot face up) is left as it was. Door and tall-plant halves are left alone: BG2 copies
     * both halves as the lower one (GadgetUtils.cleanBlockState), and flipping that would paste half-doors.
     */
    static BlockState upsideDown(BlockState state) {
        for (Property<?> property : state.getProperties())
            state = flipValue(state, property);
        if (state.hasProperty(BlockStateProperties.UP) && state.hasProperty(BlockStateProperties.DOWN)) {
            boolean up = state.getValue(BlockStateProperties.UP);
            state = state.setValue(BlockStateProperties.UP, state.getValue(BlockStateProperties.DOWN)).setValue(BlockStateProperties.DOWN, up);
        }
        if (state.hasProperty(BlockStateProperties.HANGING))
            state = state.cycle(BlockStateProperties.HANGING);
        return state;
    }

    private static <T extends Comparable<T>> BlockState flipValue(BlockState state, Property<T> property) {
        Object flipped = switch ((Object) state.getValue(property)) {
            case Half half -> half == Half.TOP ? Half.BOTTOM : Half.TOP;
            case SlabType slab -> slab == SlabType.TOP ? SlabType.BOTTOM : slab == SlabType.BOTTOM ? SlabType.TOP : slab;
            case AttachFace face -> face == AttachFace.FLOOR ? AttachFace.CEILING : face == AttachFace.CEILING ? AttachFace.FLOOR : face;
            case BellAttachType bell -> bell == BellAttachType.FLOOR ? BellAttachType.CEILING : bell == BellAttachType.CEILING ? BellAttachType.FLOOR : bell;
            case Direction direction -> direction.getAxis() == Direction.Axis.Y ? direction.getOpposite() : direction;
            case Object other -> other;
        };
        Class<T> type = property.getValueClass();
        return type.isInstance(flipped) && property.getPossibleValues().contains(type.cast(flipped))
                ? state.setValue(property, type.cast(flipped)) : state;
    }
}
