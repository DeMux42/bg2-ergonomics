package io.github.demux42.bg2ergonomics.network;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardHistory;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client asks the server to act on its clipboard history. {@code entryId} is ignored by actions that take none.
 */
public record ClipboardActionPayload(Action action, UUID entryId) implements CustomPacketPayload {
    public enum Action {
        SAVE,   //Save the held gadget's copy if it is not in the history yet, then resend the list
        LOAD,   //Put entry into the held gadget and switch it to paste mode
        DELETE, //Remove entry
        CLEAR,  //Remove every entry
        PREVIEW //Send what entry's thumbnail is drawn from (ClipboardPreviewPayload)
    }

    public static final UUID NONE = new UUID(0, 0);

    public static final Type<ClipboardActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BG2Ergonomics.MODID, "clipboard_action"));

    public static final StreamCodec<FriendlyByteBuf, ClipboardActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), ClipboardActionPayload::action,
            UUIDUtil.STREAM_CODEC, ClipboardActionPayload::entryId,
            ClipboardActionPayload::new
    );

    @Override
    public Type<ClipboardActionPayload> type() {
        return TYPE;
    }

    public static void handle(ClipboardActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack gadget = BaseGadget.getGadget(player);
            switch (payload.action()) {
                case SAVE -> {
                    ClipboardHistory.savePending(player, gadget);
                    ClipboardHistory.sync(player); //Always answer: the screen waits for this list
                }
                case LOAD -> ClipboardHistory.load(player, gadget, payload.entryId());
                case DELETE -> ClipboardHistory.delete(player, payload.entryId());
                case CLEAR -> ClipboardHistory.clear(player);
                case PREVIEW -> ClipboardHistory.sendPreview(player, payload.entryId());
            }
        });
    }
}
