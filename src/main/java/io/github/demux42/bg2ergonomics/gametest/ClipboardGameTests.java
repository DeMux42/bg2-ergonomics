package io.github.demux42.bg2ergonomics.gametest;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.clipboard.BoundedCache;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardData;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardEntry;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardHistory;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardPreview;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.setup.Registration;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.direwolf20.buildinggadgets2.util.modes.Copy;
import com.direwolf20.buildinggadgets2.util.modes.Paste;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Run with {@code gradlew runGameTestServer}. Drives the clipboard against real Building Gadgets 2 world data.
 */
@GameTestHolder(BG2Ergonomics.MODID)
@PrefixGameTestTemplate(false)
public class ClipboardGameTests {
    @GameTest(template = "empty")
    public static void thumbnails(GameTestHelper helper) {
        ClipboardPreview small = ClipboardPreview.of(cube(3));
        helper.assertTrue(small.scale() == 1 && small.cubes().size() == 26,
                "a 3x3x3 stone cube keeps its 26 outer blocks, not the hidden middle: " + small.cubes().size());
        //40^3 has 9,128 outer blocks, over the cap: thinned to 2x2x2 cubes, the 20^3 grid's 2,168 outer cells
        ClipboardPreview big = ClipboardPreview.of(cube(40));
        helper.assertTrue(big.scale() == 2 && big.cubes().size() == 2168, "big copies are thinned: scale " + big.scale() + ", " + big.cubes().size());
        helper.assertTrue(big.cubes().size() <= ClipboardPreview.MAX_CUBES, "never over the cap");

        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        ClipboardPreview.STREAM_CODEC.encode(wire, small);
        helper.assertTrue(small.equals(ClipboardPreview.STREAM_CODEC.decode(wire)), "survives the network");
        FriendlyByteBuf hostile = new FriendlyByteBuf(Unpooled.buffer());
        hostile.writeVarInt(1);
        hostile.writeVarInt(0);
        hostile.writeVarInt(Integer.MAX_VALUE); //A cube count meant to make the client allocate
        try {
            ClipboardPreview.STREAM_CODEC.decode(hostile);
            helper.fail("an oversized preview was accepted");
        } catch (DecoderException expected) {
        }

        //The leak guard: every value the cache lets go of is released, exactly once
        List<String> released = new ArrayList<>();
        BoundedCache<Integer, String> cache = new BoundedCache<>(2, released::add);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.get(1);
        cache.put(3, "c"); //Over the limit: 2 is the least recently used
        cache.put(1, "a2"); //Replaced
        cache.retain(List.of(3)); //Entry 1 left the history
        helper.assertTrue(released.equals(List.of("b", "a", "a2")) && cache.size() == 1, "released " + released);
        cache.clear(); //Logout
        helper.assertTrue(released.equals(List.of("b", "a", "a2", "c")) && cache.size() == 0, "released " + released);
        helper.succeed();
    }

    private static List<StatePos> cube(int size) {
        List<StatePos> blocks = new ArrayList<>();
        for (int x = 0; x < size; x++)
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++)
                    blocks.add(new StatePos(Blocks.STONE.defaultBlockState(), new BlockPos(x, y, z)));
        return blocks;
    }

    @GameTest(template = "empty")
    public static void clipboardHistory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BG2Data bg2 = BG2Data.get(server.overworld());
        ClipboardData data = ClipboardData.get(server);
        ServerPlayer player = fakePlayer(helper);
        ItemStack gadget = new ItemStack(Registration.CopyPaste_Gadget.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, gadget);
        UUID gadgetId = GadgetNBT.getUUID(gadget);

        //Copy A (two stone and an air gap) while still in copy mode: not saved yet
        setCopy(bg2, gadget, List.of(
                new StatePos(Blocks.STONE.defaultBlockState(), new BlockPos(0, 0, 0)),
                new StatePos(Blocks.STONE.defaultBlockState(), new BlockPos(1, 0, 0)),
                new StatePos(Blocks.AIR.defaultBlockState(), new BlockPos(2, 0, 0))));
        ClipboardHistory.checkHeldGadget(player);
        helper.assertTrue(data.history(player.getUUID()).isEmpty(), "copy mode does not save");

        //Switching to paste saves it once
        GadgetNBT.setMode(gadget, new Paste());
        ClipboardHistory.checkHeldGadget(player);
        ClipboardHistory.checkHeldGadget(player);
        List<ClipboardEntry> history = data.history(player.getUUID());
        helper.assertTrue(history.size() == 1, "one entry after switching to paste, got " + history.size());
        ClipboardEntry a = history.get(0);
        helper.assertTrue(a.blockCount() == 2, "air is not counted, got " + a.blockCount());
        helper.assertTrue(a.size().equals(new BlockPos(3, 1, 1)), "size is the bounding box, got " + a.size());
        helper.assertTrue(a.iconBlock().equals("minecraft:stone"), "icon is the most common block, got " + a.iconBlock());
        helper.assertTrue(a.id().equals(ClipboardHistory.heldEntry(data, gadget)), "gadget holds A");

        //BG2's rotate gives the copy a new UUID; staying in paste mode must not add an entry per rotation
        GadgetNBT.setCopyUUID(gadget);
        ClipboardHistory.checkHeldGadget(player);
        helper.assertTrue(data.history(player.getUUID()).size() == 1, "rotating in paste mode adds no entry");

        //Copy B replaces the gadget's data; loading A must save B first, then put A back
        GadgetNBT.setMode(gadget, new Copy());
        ClipboardHistory.checkHeldGadget(player);
        setCopy(bg2, gadget, List.of(new StatePos(Blocks.DIRT.defaultBlockState(), BlockPos.ZERO)));
        ClipboardHistory.load(player, gadget, a.id());
        history = data.history(player.getUUID());
        helper.assertTrue(history.size() == 2 && history.get(1).id().equals(a.id()), "B saved on top of A");
        helper.assertTrue(bg2.getCopyPasteList(gadgetId, false).size() == 3, "gadget holds A's blocks again");
        helper.assertTrue(GadgetNBT.getMode(gadget) instanceof Paste, "loading switches to paste");
        helper.assertTrue(a.id().equals(ClipboardHistory.heldEntry(data, gadget)), "gadget holds A, not pending");

        //The stored entry is independent of the gadget's live blocks (BG2's Template replace edits those in place)
        bg2.getCopyPasteList(gadgetId, false).get(0).state = Blocks.GLASS.defaultBlockState();
        ClipboardHistory.load(player, gadget, a.id());
        helper.assertTrue(bg2.getCopyPasteList(gadgetId, false).get(0).state.is(Blocks.STONE), "entry unaffected by edits to the gadget");

        //Another player cannot load someone else's entry
        ServerPlayer other = fakePlayer(helper);
        ItemStack otherGadget = new ItemStack(Registration.CopyPaste_Gadget.get());
        other.setItemInHand(InteractionHand.MAIN_HAND, otherGadget);
        ClipboardHistory.load(other, otherGadget, a.id());
        helper.assertTrue(bg2.getCopyPasteList(GadgetNBT.getUUID(otherGadget), false) == null, "foreign entry refused");

        //History and blocks survive a world save and reload
        CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        ClipboardData reloaded = ClipboardData.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.history(player.getUUID()).equals(history), "history round-trips");
        helper.assertTrue(reloaded.blocks(a.id()).equals(data.blocks(a.id())), "blocks round-trip");
        helper.assertTrue(reloaded.held(gadgetId).equals(data.held(gadgetId)), "held entry round-trips");

        //A full history drops the oldest entry and its blocks
        ClipboardEntry c = ClipboardEntry.of(UUID.randomUUID(), bg2.getCopyPasteList(gadgetId, false));
        data.add(player.getUUID(), c, BG2Data.statePosListToNBTMapArray(bg2.getCopyPasteList(gadgetId, false)), 2);
        history = data.history(player.getUUID());
        helper.assertTrue(history.size() == 2 && history.get(0).id().equals(c.id()), "newest first, capped at 2");
        helper.assertTrue(data.blocks(a.id()) == null, "evicted entry's blocks are deleted");

        data.clear(player.getUUID());
        helper.assertTrue(data.history(player.getUUID()).isEmpty(), "clear empties the history");
        helper.succeed();
    }

    /**
     * Not GameTestHelper.makeMockServerPlayerInLevel: logging that in makes Curios push a payload the mock client
     * never negotiated, which NeoForge rejects.
     */
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "clipboard-test"));
    }

    private static void setCopy(BG2Data bg2, ItemStack gadget, List<StatePos> blocks) {
        bg2.addToCopyPaste(GadgetNBT.getUUID(gadget), new ArrayList<>(blocks));
        GadgetNBT.setCopyUUID(gadget);
    }
}
