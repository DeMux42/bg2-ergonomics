package io.github.demux42.bg2ergonomics.network;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.client.ClipboardThumbnails;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardPreview;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server sends what one clipboard entry's thumbnail is drawn from, in answer to {@link ClipboardActionPayload.Action#PREVIEW}.
 */
public record ClipboardPreviewPayload(UUID entryId, ClipboardPreview preview) implements CustomPacketPayload {
    public static final Type<ClipboardPreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BG2Ergonomics.MODID, "clipboard_preview"));

    public static final StreamCodec<FriendlyByteBuf, ClipboardPreviewPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, ClipboardPreviewPayload::entryId,
            ClipboardPreview.STREAM_CODEC, ClipboardPreviewPayload::preview,
            ClipboardPreviewPayload::new
    );

    @Override
    public Type<ClipboardPreviewPayload> type() {
        return TYPE;
    }

    public static void handle(ClipboardPreviewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClipboardThumbnails.receive(payload.entryId(), payload.preview()));
    }
}
