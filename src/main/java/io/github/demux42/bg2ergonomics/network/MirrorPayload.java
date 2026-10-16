package io.github.demux42.bg2ergonomics.network;

import io.github.demux42.bg2ergonomics.BG2Ergonomics;
import io.github.demux42.bg2ergonomics.controls.Mirroring;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client asks the server to mirror the held gadget's copy: left to right as the player faces, or upside down.
 */
public record MirrorPayload(boolean vertical) implements CustomPacketPayload {
    public static final Type<MirrorPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BG2Ergonomics.MODID, "mirror"));

    public static final StreamCodec<ByteBuf, MirrorPayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(MirrorPayload::new, MirrorPayload::vertical);

    @Override
    public Type<MirrorPayload> type() {
        return TYPE;
    }

    public static void handle(MirrorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            //Left to right as seen by the player: facing north, that is the east-west (X) axis
            Direction.Axis axis = payload.vertical() ? Direction.Axis.Y : player.getDirection().getClockWise().getAxis();
            Mirroring.mirrorGadget(player, BaseGadget.getGadget(player), axis);
        });
    }
}
