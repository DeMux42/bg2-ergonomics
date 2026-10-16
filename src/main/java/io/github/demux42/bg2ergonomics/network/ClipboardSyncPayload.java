package io.github.demux42.bg2ergonomics.network;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.client.ClientEvents;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardEntry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * Server sends a player their own clipboard history, newest first, and which entry their held gadget holds
 * ({@link ClipboardActionPayload#NONE} for none or an unsaved copy).
 */
public record ClipboardSyncPayload(List<ClipboardEntry> entries, UUID heldEntry) implements CustomPacketPayload {
    public static final Type<ClipboardSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BG2Ergonomics.MODID, "clipboard_sync"));

    public static final StreamCodec<FriendlyByteBuf, ClipboardSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ClipboardEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), ClipboardSyncPayload::entries,
            UUIDUtil.STREAM_CODEC, ClipboardSyncPayload::heldEntry,
            ClipboardSyncPayload::new
    );

    @Override
    public Type<ClipboardSyncPayload> type() {
        return TYPE;
    }

    public static void handle(ClipboardSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEvents.setClipboard(payload.entries(), payload.heldEntry()));
    }
}
