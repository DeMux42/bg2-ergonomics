package io.github.demux42.bg2ergonomics.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.demux42.bg2ergonomics.clipboard.BoundedCache;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardEntry;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardPreview;
import io.github.demux42.bg2ergonomics.clipboard.ClipboardPreview.Cube;
import io.github.demux42.bg2ergonomics.network.ClipboardActionPayload;
import io.github.demux42.bg2ergonomics.network.ClipboardPreviewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Pictures of the clipboard history's copies, so the list can be read at a glance. The server sends a cut-down
 * copy of each entry ({@link ClipboardPreview}); it is drawn once, the way a block looks in the inventory, into a
 * small texture. Fetching and drawing start as soon as the history arrives, so the pictures are ready before the
 * list is opened. Drawing is spread over frames, a few milliseconds each, so it never stalls the game.
 * <p>
 * Bounded for long sessions: at most {@link #MAX_TEXTURES} pictures, least recently shown dropped first, and every
 * picture let go of has its texture released ({@link BoundedCache}): on overflow, when its entry leaves the history,
 * and on logout. Copies waiting to be drawn are only kept for entries still in the history. The one off-screen
 * target and vertex buffer that draw them are shared, and freed on logout.
 */
public final class ClipboardThumbnails {
    public static final int SIZE = 128; //Texture pixels
    static final int MAX_SHOWN = 20; //ClipboardGUI shows at most 5 rows of 4
    private static final int PREFETCH = 28; //Newest entries drawn ahead of time; covers the default history of 20
    private static final int MAX_TEXTURES = PREFETCH + MAX_SHOWN; //So drawing ahead never pushes out a tile on screen
    private static final int MAX_IN_FLIGHT = 4;
    private static final int MAX_TRIES = 3;
    private static final long RETRY_MS = 3000;
    private static final long FRAME_BUDGET_NS = 3_000_000;

    private static final BoundedCache<UUID, ResourceLocation> textures = new BoundedCache<>(MAX_TEXTURES,
            location -> Minecraft.getInstance().getTextureManager().release(location)); //Frees the GPU texture and its pixels
    private static final Map<UUID, ClipboardPreview> waiting = new LinkedHashMap<>();
    private static final Map<UUID, Request> requests = new HashMap<>();
    private static final Set<UUID> shown = new LinkedHashSet<>(); //Tiles on screen without a picture, asked for first
    private static Job job;
    private static TextureTarget target;
    private static ByteBufferBuilder vertices;

    private ClipboardThumbnails() {
    }

    private static final class Request {
        long at;
        int tries;
    }

    /**
     * The entry's picture, or null while it is not drawn yet.
     */
    static ResourceLocation texture(UUID id) {
        ResourceLocation location = textures.get(id);
        if (location == null) shown.add(id);
        return location;
    }

    /**
     * A new history arrived: let go of everything for entries no longer in it.
     */
    static void sync(List<ClipboardEntry> entries) {
        Set<UUID> ids = new HashSet<>();
        for (ClipboardEntry entry : entries) ids.add(entry.id());
        textures.retain(ids);
        waiting.keySet().retainAll(ids);
        requests.keySet().retainAll(ids);
        shown.retainAll(ids);
        if (job != null && !ids.contains(job.id)) job = null;
    }

    /**
     * Asks the server for missing pictures, those on screen first, a few at a time.
     */
    static void tick() {
        if (!ClientEvents.serverHas(ClipboardPreviewPayload.TYPE)) return;
        long now = System.currentTimeMillis();
        int inFlight = 0;
        for (Request request : requests.values())
            if (now - request.at < RETRY_MS) inFlight++;
        List<UUID> wanted = new ArrayList<>(shown);
        shown.clear();
        List<ClipboardEntry> entries = ClientEvents.clipboard();
        for (int i = 0; i < Math.min(PREFETCH, entries.size()); i++) wanted.add(entries.get(i).id());
        for (UUID id : wanted) {
            if (inFlight >= MAX_IN_FLIGHT) return;
            if (textures.contains(id) || waiting.containsKey(id) || (job != null && job.id.equals(id))) continue;
            Request request = requests.computeIfAbsent(id, k -> new Request());
            if (request.tries >= MAX_TRIES || (request.tries > 0 && now - request.at < RETRY_MS)) continue;
            request.at = now;
            request.tries++;
            PacketDistributor.sendToServer(new ClipboardActionPayload(ClipboardActionPayload.Action.PREVIEW, id));
            inFlight++;
        }
    }

    public static void receive(UUID id, ClipboardPreview preview) {
        if (ClipboardEntry.indexOf(ClientEvents.clipboard(), id) < 0 || textures.contains(id)) return; //Deleted meanwhile, or a repeat
        requests.remove(id);
        waiting.put(id, preview);
    }

    static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (Minecraft.getInstance().level == null) return;
        long deadline = System.nanoTime() + FRAME_BUDGET_NS;
        while (System.nanoTime() < deadline) {
            if (job == null) {
                Iterator<Map.Entry<UUID, ClipboardPreview>> next = waiting.entrySet().iterator();
                if (!next.hasNext()) return;
                Map.Entry<UUID, ClipboardPreview> entry = next.next();
                next.remove();
                if (!entry.getValue().cubes().isEmpty()) job = new Job(entry.getKey(), entry.getValue());
                continue;
            }
            if (!job.draw(deadline)) return; //Out of time: carry on next frame
            textures.put(job.id, job.finish());
            job = null;
        }
    }

    /**
     * Logout: release every texture and the drawing target and buffer.
     */
    static void clear() {
        textures.clear();
        waiting.clear();
        requests.clear();
        shown.clear();
        job = null;
        if (target != null) target.destroyBuffers();
        if (vertices != null) vertices.close();
        target = null;
        vertices = null;
    }

    /**
     * One picture being drawn, possibly over several frames into the shared target.
     */
    private static final class Job {
        final UUID id;
        final List<Cube> cubes;
        final int scale;
        final PoseStack pose = new PoseStack();
        final Matrix4f projection;
        int next;

        Job(UUID id, ClipboardPreview preview) {
            this.id = id;
            this.cubes = preview.cubes();
            this.scale = preview.scale();
            int maxX = 0, maxY = 0, maxZ = 0;
            for (Cube cube : cubes) {
                maxX = Math.max(maxX, cube.x());
                maxY = Math.max(maxY, cube.y());
                maxZ = Math.max(maxZ, cube.z());
            }
            float w = (maxX + 1) * scale, h = (maxY + 1) * scale, d = (maxZ + 1) * scale;
            //Turned like a block in the inventory, and fitted to the picture from its turned corners
            Quaternionf turn = new Quaternionf().rotationXYZ((float) Math.toRadians(30), (float) Math.toRadians(225), 0);
            Vector3f corner = new Vector3f();
            float spanX = 0, spanY = 0, spanZ = 0;
            for (int i = 0; i < 8; i++) {
                corner.set((i & 1) == 0 ? -w / 2 : w / 2, (i & 2) == 0 ? -h / 2 : h / 2, (i & 4) == 0 ? -d / 2 : d / 2).rotate(turn);
                spanX = Math.max(spanX, Math.abs(corner.x));
                spanY = Math.max(spanY, Math.abs(corner.y));
                spanZ = Math.max(spanZ, Math.abs(corner.z));
            }
            float fit = 0.94f / Math.max(spanX, spanY);
            //Y flipped as GUI items are, so the inventory lighting fits; the projection flips it back
            pose.scale(fit, -fit, fit);
            pose.mulPose(turn);
            pose.translate(-w / 2, -h / 2, -d / 2);
            float depth = spanZ * fit + 1;
            projection = new Matrix4f().setOrtho(-1, 1, 1, -1, -depth, depth);
        }

        /**
         * Draws on until done or {@code deadline}; true when done.
         */
        boolean draw(long deadline) {
            Minecraft mc = Minecraft.getInstance();
            if (target == null) {
                target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
                vertices = new ByteBufferBuilder(1 << 18);
            }
            if (next == 0) {
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
            }
            target.bindWrite(true);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            Matrix4fStack view = RenderSystem.getModelViewStack();
            view.pushMatrix();
            view.identity();
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(vertices);
            BlockRenderDispatcher blocks = mc.getBlockRenderer();
            try {
                while (next < cubes.size()) {
                    Cube cube = cubes.get(next++);
                    pose.pushPose();
                    pose.translate(cube.x() * scale, cube.y() * scale, cube.z() * scale);
                    pose.scale(scale, scale, scale);
                    try {
                        blocks.renderSingleBlock(cube.state(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    } catch (RuntimeException ignored) {
                        //A modded block that cannot draw outside a world is left out of the picture
                    }
                    pose.popPose();
                    if ((next & 15) == 0 && System.nanoTime() > deadline) break;
                }
                buffers.endBatch();
            } finally {
                view.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
                mc.getMainRenderTarget().bindWrite(true);
            }
            return next >= cubes.size();
        }

        /**
         * Copies the drawn picture into its own texture, so the target can draw the next one.
         */
        ResourceLocation finish() {
            NativeImage image = new NativeImage(SIZE, SIZE, false);
            target.bindRead();
            image.downloadTexture(0, false);
            target.unbindRead();
            image.flipY();
            DynamicTexture texture = new DynamicTexture(image); //Owns the image: both are freed when the texture is released
            texture.setFilter(true, false);
            return Minecraft.getInstance().getTextureManager().register("bg2ergonomics_clipboard", texture);
        }
    }
}
