package io.github.demux42.bg2ergonomics.gametest;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.CopyLimits;
import io.github.demux42.bg2ergonomics.controls.Aim;
import io.github.demux42.bg2ergonomics.client.PreviewFit;
import io.github.demux42.bg2ergonomics.controls.Box;
import io.github.demux42.bg2ergonomics.controls.DestructionSize;
import io.github.demux42.bg2ergonomics.controls.DestructionSize.Side;
import io.github.demux42.bg2ergonomics.controls.Glide;
import io.github.demux42.bg2ergonomics.controls.Mirroring;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.setup.Registration;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.GadgetNBT.IntSettings;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.direwolf20.buildinggadgets2.util.datatypes.TagPos;
import com.direwolf20.buildinggadgets2.util.modes.Copy;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The math behind the controls, mirroring and the Template Manager preview: which corner a face push moves, the copy
 * limits, which Destruction size a key or the crosshair picks, how a flip moves blocks and turns their states, and
 * where the preview camera sits. The input handling and rendering around it are client-only and need an in-game check.
 */
@GameTestHolder(BG2Ergonomics.MODID)
@PrefixGameTestTemplate(false)
public class ControlsGameTests {
    @GameTest(template = "empty")
    public static void selectionMath(GameTestHelper helper) {
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(2, 1, 3));
        check(helper, box.pushFace(Direction.EAST, 1), new Box(new BlockPos(0, 0, 0), new BlockPos(3, 1, 3)), "east face grows the high corner");
        check(helper, box.pushFace(Direction.WEST, 2), new Box(new BlockPos(-2, 0, 0), new BlockPos(2, 1, 3)), "west face grows the low corner");
        check(helper, box.pushFace(Direction.EAST, -2), new Box(new BlockPos(0, 0, 0), new BlockPos(0, 1, 3)), "pulling stops at one block thick");
        check(helper, box.pushFace(Direction.EAST, -10), new Box(new BlockPos(-8, 0, 0), new BlockPos(-8, 1, 3)), "pulling past one block drags the box west");
        Box thin = new Box(new BlockPos(4, 0, 0), new BlockPos(4, 1, 3));
        check(helper, thin.pushFace(Direction.WEST, -1), new Box(new BlockPos(5, 0, 0), new BlockPos(5, 1, 3)), "a one-thick box pulled from the west moves east");
        check(helper, thin.pushFace(Direction.WEST, 1), new Box(new BlockPos(3, 0, 0), new BlockPos(4, 1, 3)), "and pushing it grows again");
        check(helper, box.pushFace(Direction.UP, -1), new Box(new BlockPos(0, 0, 0), new BlockPos(2, 0, 3)), "top face pulls in");
        //Start is BG2's paste origin, so a corner keeps its role even when start is the high one
        Box reversed = new Box(new BlockPos(5, 0, 0), new BlockPos(0, 0, 0));
        check(helper, reversed.pushFace(Direction.EAST, 1), new Box(new BlockPos(6, 0, 0), new BlockPos(0, 0, 0)), "high start moves on east push");
        check(helper, box.move(Direction.NORTH, 4), new Box(new BlockPos(0, 0, -4), new BlockPos(2, 1, -1)), "move shifts both corners");
        check(helper, box.size(Direction.Axis.Z), 4, "sizes are inclusive");

        //The outline's ease: starts where it was, lands most of a step in one 60 fps frame without overshoot, then sits on the block
        AABB from = new AABB(0, 0, 0, 1, 1, 1), to = new AABB(0, 0, 0, 4, 1, 1);
        check(helper, Glide.at(from, to, 0), from, "the glide starts from where the outline was");
        double oneFrame = Glide.at(from, to, 16.7).maxX;
        helper.assertTrue(oneFrame > 2.5 && oneFrame < 4, "most of the step lands in the first frame: " + oneFrame);
        check(helper, Glide.at(from, to, Glide.DONE_MS), to, "then the outline sits exactly on the block");
        helper.assertTrue(Glide.pulse(0) == 1 && Glide.pulse(400) < 0.01, "the flash fades");

        //Same limits as BG2's Copy.collectWorld: corner distance per axis, then blocks in the box
        int limit = CopyLimits.BG2_BLOCKS;
        check(helper, CopyLimits.copyBlocks(), limit, "without Building Gadgets Extra the Copy limit is BG2's");
        helper.assertTrue(new Box(BlockPos.ZERO, new BlockPos(500, 0, 0)).fitsBG2(limit), "500 apart fits");
        helper.assertFalse(new Box(BlockPos.ZERO, new BlockPos(501, 0, 0)).fitsBG2(limit), "501 apart is refused");
        helper.assertTrue(new Box(BlockPos.ZERO, new BlockPos(99, 99, 9)).fitsBG2(limit), "100,000 blocks fits");
        helper.assertFalse(new Box(BlockPos.ZERO, new BlockPos(99, 99, 10)).fitsBG2(limit), "110,000 blocks is refused");
        helper.assertTrue(new Box(BlockPos.ZERO, new BlockPos(99, 99, 10)).fitsBG2(1000000), "a raised limit is followed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mirrorMath(GameTestHelper helper) {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        CompoundTag chestData = new CompoundTag();
        chestData.putString("marker", "chest");
        //A row along X, start corner first and the opposite corner last, as BG2 lists a copy
        List<StatePos> row = List.of(
                new StatePos(stairs, new BlockPos(0, 0, 0)),
                new StatePos(Blocks.AIR.defaultBlockState(), new BlockPos(1, 0, 0)),
                new StatePos(Blocks.CHEST.defaultBlockState(), new BlockPos(2, 0, 0)));
        List<TagPos> tags = List.of(new TagPos(chestData, new BlockPos(2, 0, 0)));
        ArrayList<StatePos> mirrored = Mirroring.mirror(row, tags, Direction.Axis.X);
        check(helper, mirrored.get(0).pos, new BlockPos(2, 0, 0), "flipped in place: the stairs move to the other end");
        check(helper, mirrored.get(0).state.getValue(StairBlock.FACING), Direction.WEST, "east-facing stairs face west");
        check(helper, mirrored.get(2).pos, BlockPos.ZERO, "list order is kept, so first and last stay opposite corners");
        check(helper, tags.get(0).pos, BlockPos.ZERO, "block-entity data moves with its chest");
        //BG2 rebuilds a copy from its first and last entries when it saves or sends it: the flip must survive that
        ArrayList<StatePos> roundTrip = BG2Data.statePosListFromNBTMapArray(BG2Data.statePosListToNBTMapArray(mirrored));
        helper.assertTrue(roundTrip.stream().anyMatch(s -> s.pos.equals(new BlockPos(2, 0, 0)) && s.state.equals(mirrored.get(0).state)),
                "BG2's save format keeps the flipped copy");

        check(helper, Mirroring.flip(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH), Direction.Axis.Z)
                .getValue(StairBlock.FACING), Direction.NORTH, "Z flips north and south");

        Direction.Axis up = Direction.Axis.Y;
        check(helper, Mirroring.flip(Blocks.OAK_SLAB.defaultBlockState(), up).getValue(SlabBlock.TYPE), SlabType.TOP, "bottom slab becomes top");
        check(helper, Mirroring.flip(stairs, up).getValue(StairBlock.HALF), Half.TOP, "stairs turn upside down");
        check(helper, Mirroring.flip(stairs, up).getValue(StairBlock.FACING), Direction.EAST, "and keep their facing");
        check(helper, Mirroring.flip(Blocks.OAK_DOOR.defaultBlockState(), up).getValue(DoorBlock.HALF), DoubleBlockHalf.LOWER, "BG2 copies both door halves as lower, so they stay");
        check(helper, Mirroring.flip(Blocks.HOPPER.defaultBlockState(), up).getValue(HopperBlock.FACING), Direction.DOWN, "a hopper cannot face up, so it stays");
        check(helper, Mirroring.flip(Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP), up)
                .getValue(PistonBaseBlock.FACING), Direction.DOWN, "an upward piston points down");
        check(helper, Mirroring.flip(Blocks.LANTERN.defaultBlockState(), up).getValue(LanternBlock.HANGING), true, "a standing lantern hangs");

        //Through BG2's data, as the Mirror button does it
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "mirror-test"));
        ItemStack gadget = new ItemStack(Registration.CopyPaste_Gadget.get());
        BG2Data bg2 = BG2Data.get(helper.getLevel().getServer().overworld());
        bg2.addToCopyPaste(GadgetNBT.getUUID(gadget), new ArrayList<>(row));
        GadgetNBT.setCopyUUID(gadget);
        UUID before = GadgetNBT.getCopyUUID(gadget);
        Mirroring.mirrorGadget(player, gadget, Direction.Axis.X);
        check(helper, bg2.getCopyPasteList(GadgetNBT.getUUID(gadget), false).get(0).pos, new BlockPos(2, 0, 0), "the gadget's copy is mirrored");
        helper.assertFalse(before.equals(GadgetNBT.getCopyUUID(gadget)), "a new copy id makes the client fetch the mirrored copy");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void copyKeepsOpen(GameTestHelper helper) {
        //A real BG2 copy, so this also checks that the CopyMixin applies
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(0, 1, 0));
        level.setBlock(start, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true), 2);
        level.setBlock(start.east(), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true), 2);
        level.setBlock(start.east(2), Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true), 2);
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "copy-test"));
        ItemStack gadget = new ItemStack(Registration.CopyPaste_Gadget.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, gadget);
        GadgetNBT.setCopyStartPos(gadget, start);
        GadgetNBT.setCopyEndPos(gadget, start.east(2));

        ArrayList<StatePos> copy = new Copy().collectWorld(Direction.UP, player, start, Blocks.AIR.defaultBlockState());
        for (int x = 0; x <= 2; x++) level.setBlock(start.east(x), Blocks.AIR.defaultBlockState(), 2);
        check(helper, copy.size(), 3, "three blocks copied");
        check(helper, copy.get(0).state.getValue(TrapDoorBlock.OPEN), true, "an open trapdoor stays open");
        check(helper, copy.get(1).state.getValue(FenceGateBlock.OPEN), true, "an open fence gate stays open");
        check(helper, copy.get(2).state.getValue(CandleBlock.LIT), false, "BG2 still cleans everything else (a lit candle copies unlit)");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void templatePreviewFit(GameTestHelper helper) {
        //BG2 lists a template from one corner to the opposite one; either order gives the same fit
        PreviewFit fit = PreviewFit.of(new BlockPos(0, 0, 0), new BlockPos(9, 1, 3), 60);
        check(helper, fit.centre(), new Vec3(5, 1, 2), "centre of a 10 x 2 x 4 template");
        check(helper, PreviewFit.of(new BlockPos(9, 1, 3), BlockPos.ZERO, 60).centre(), fit.centre(), "corner order does not matter");
        check(helper, PreviewFit.of(new BlockPos(0, 0, 0), new BlockPos(-9, 1, -3), 60).centre(), new Vec3(-4, 1, -1), "a copy can run negative");
        //A 60 degree view shows a sphere of radius r from 2r away
        helper.assertTrue(Math.abs(fit.distance() - 2 * fit.radius()) < 1e-3, "backs off to fit the whole template, got " + fit.distance());
        check(helper, PreviewFit.of(BlockPos.ZERO, BlockPos.ZERO, 60).step(), 1f, "small templates zoom and pan like BG2");
        helper.assertTrue(PreviewFit.of(BlockPos.ZERO, new BlockPos(199, 9, 0), 60).step() > 10, "a 200 block wall zooms faster");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void destructionMath(GameTestHelper helper) {
        //Looking north at a wall: its south face
        Direction wall = Direction.SOUTH, facing = Direction.NORTH;
        //Crosshair thirds on the wall face (offset from the block centre; the face is at z +0.5)
        check(helper, DestructionSize.aimed(wall, facing, new Vec3(0.4, 0, 0.5)), new Side(IntSettings.RIGHT, 1), "right third");
        check(helper, DestructionSize.aimed(wall, facing, new Vec3(0, -0.4, 0.5)), new Side(IntSettings.DOWN, 1), "bottom third");
        check(helper, DestructionSize.aimed(wall, facing, new Vec3(0.1, 0.1, 0.5)), new Side(IntSettings.DEPTH, 1), "centre is depth");

        DestructionSize size = new DestructionSize(0, 0, 0, 0, 1);
        check(helper, size.adjust(IntSettings.DEPTH, -5).depth(), 1, "depth never reaches 0, where BG2 destroys nothing");
        check(helper, size.adjust(IntSettings.LEFT, 20).left(), DestructionSize.MAX, "sizes stop at BG2's slider maximum");
        check(helper, size.adjust(IntSettings.RIGHT, 3).dimensions(), "4 x 1 x 1", "dimensions as BG2 shows them");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aimMath(GameTestHelper helper) {
        AABB box = new AABB(0, 0, 0, 4, 4, 4); //Centre (2, 2, 2); the south side is at z 4
        Vec3 front = new Vec3(2, 2, 10);
        check(helper, aim(front, new Vec3(2, 2, 4), box), Direction.SOUTH, "the middle of the front picks the front");
        check(helper, aim(front, new Vec3(0.5, 2, 4), box), Direction.WEST, "the left quarter of the front picks the left, through the front");
        check(helper, aim(front, new Vec3(2, 3.6, 4), box), Direction.UP, "the top quarter of the front picks the top");
        check(helper, aim(front, new Vec3(-1, 2, 4), box), Direction.WEST, "just past the left edge picks the left");
        helper.assertTrue(aim(front, new Vec3(-20, 2, 4), box) == null, "far past the box picks nothing");
        //From above at 45 degrees the top and front are both in view: their shared edge never flips between them
        Vec3 above = new Vec3(2, 10, 10);
        check(helper, aim(above, new Vec3(2, 4, 3.5), box), Direction.UP, "the top's band by the front stays the top");
        check(helper, aim(above, new Vec3(2, 3.5, 4), box), Direction.SOUTH, "the front's band by the top stays the front");
        check(helper, aim(above, new Vec3(2, 4, 0.5), box), Direction.NORTH, "the far quarter of the top picks the back");
        //Just above the top: it is a sliver, so the front's top quarter reaches it
        check(helper, aim(new Vec3(2, 4.3, 10), new Vec3(2, 3.5, 4), box), Direction.UP, "an edge-on top is reached from the front");
        //Looking at the south-east corner: both sides in view, so the east side near the corner stays east
        check(helper, aim(new Vec3(10, 2, 10), new Vec3(4, 2, 3.5), box), Direction.EAST, "two sides in view keep their own edges");
        check(helper, aim(new Vec3(2, 2, 2), new Vec3(2, 2, -5), box), Direction.NORTH, "from inside, the side looked at");
        helper.succeed();
    }

    private static Direction aim(Vec3 eye, Vec3 target, AABB box) {
        return Aim.face(eye, target.subtract(eye).normalize(), box);
    }

    private static void check(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(expected.equals(actual), what + ": expected " + expected + ", got " + actual);
    }
}
