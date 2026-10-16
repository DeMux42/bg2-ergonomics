package io.github.demux42.bg2ergonomics;

import io.github.demux42.bg2ergonomics.client.ClientEvents;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardHistory;
import io.github.demux42.bg2ergonomics.network.ClipboardActionPayload;
import io.github.demux42.bg2ergonomics.network.ClipboardPreviewPayload;
import io.github.demux42.bg2ergonomics.network.ClipboardSyncPayload;
import io.github.demux42.bg2ergonomics.network.MirrorPayload;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Add-on for Building Gadgets 2. It reads and writes BG2's public data (BG2Data, GadgetNBT) and keeps its own state
 * in its own world data, so removing it leaves BG2 items and worlds untouched. Three small patches go into BG2 itself,
 * all optional (the game keeps BG2's behaviour if they cannot apply): the Template Manager preview and the Copy/Cut
 * outline, client side (TemplateManagerGUIMixin, VBORendererMixin), and copies keeping doors, trapdoors and gates
 * open, server side (CopyMixin).
 */
@Mod(BG2Ergonomics.MODID)
public class BG2Ergonomics {
    public static final String MODID = "bg2ergonomics";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final ModConfigSpec.Builder SERVER_CONFIG = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue CLIPBOARD_SIZE = SERVER_CONFIG
            .comment("How many copies each player's Copy/Paste clipboard history keeps (0 turns it off)")
            .defineInRange("clipboardSize", 20, 0, 100);

    private static final ModConfigSpec.Builder CLIENT_CONFIG = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue CONTROLS_OVERLAY = CLIENT_CONFIG
            .comment("Show the held gadget's scroll controls on the left of the screen (also a checkbox in the gadget's G menu)")
            .define("controlsOverlay", true);

    public BG2Ergonomics(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG.build());
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_CONFIG.build());
        modBus.addListener(BG2Ergonomics::registerPayloads);
        NeoForge.EVENT_BUS.addListener(ClipboardHistory::onServerTick);
        NeoForge.EVENT_BUS.addListener(ClipboardHistory::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ClipboardHistory::onPlayerLoggedOut);
        if (FMLLoader.getDist().isClient())
            ClientEvents.init(modBus);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        //Optional: a client with this mod can still join a server without it (and the other way round); the clipboard just stays off
        PayloadRegistrar registrar = event.registrar(MODID).optional();
        registrar.playToServer(ClipboardActionPayload.TYPE, ClipboardActionPayload.STREAM_CODEC, ClipboardActionPayload::handle);
        registrar.playToClient(ClipboardSyncPayload.TYPE, ClipboardSyncPayload.STREAM_CODEC, ClipboardSyncPayload::handle);
        registrar.playToServer(MirrorPayload.TYPE, MirrorPayload.STREAM_CODEC, MirrorPayload::handle);
        registrar.playToClient(ClipboardPreviewPayload.TYPE, ClipboardPreviewPayload.STREAM_CODEC, ClipboardPreviewPayload::handle);
    }
}
