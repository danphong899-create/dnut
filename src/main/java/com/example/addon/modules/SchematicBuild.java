package com.example.addon.modules;

import com.example.addon.DonutAddon;
import com.example.addon.gui.BuildMenuScreen;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Builds a Litematica ".litematic" file, lowest layer first.
 *
 *  - Pick the file with the "Nạp schematic" button (native file dialog) or drag & drop it onto the menu.
 *  - Menu (key J), run/pause (key P), HUD with progress.
 *  - Orientation (facing, axis, half, face, sign rotation, door hinge, slab top/bottom): the module asks the game's own
 *    placement logic which view direction + clicked face gives the schematic's state, then looks that way and clicks it.
 *  - Double slabs: first half, then the second half on top of it.
 *  - Blocks with nothing to lean on: a temporary scaffold block is placed next to them and broken afterwards.
 *  - Clickable states (repeater delay, comparator mode, doors/trapdoors/gates open, lever on/off) are fixed afterwards.
 */
public class SchematicBuild extends Module {
    private record Target(BlockPos pos, BlockState state) {}
    private record Plan(float yaw, float pitch, BlockPos neighbor, Direction side, Vec3d hit) {}
    public record Need(String name, int need, int have) {}
    private static final class Fix {
        final Target t; int attempts;
        Fix(Target t) { this.t = t; }
    }

    private static final Set<String> ORIENT = Set.of("facing", "axis", "half", "face", "rotation", "hinge");
    private static final float[] YAWS4 = {0f, 90f, 180f, 270f};
    private static final float[] YAWS16 = new float[16];
    private static final float[] PITCHES = {0f, 90f, -90f};
    static { for (int i = 0; i < 16; i++) YAWS16[i] = i * 22.5f; }

    /** Blocks whose item is not the block's own item. */
    private static final Map<Block, Item> ITEM_OVERRIDE = Map.ofEntries(
        Map.entry(Blocks.WHEAT, Items.WHEAT_SEEDS),
        Map.entry(Blocks.CARROTS, Items.CARROT),
        Map.entry(Blocks.POTATOES, Items.POTATO),
        Map.entry(Blocks.BEETROOTS, Items.BEETROOT_SEEDS),
        Map.entry(Blocks.MELON_STEM, Items.MELON_SEEDS),
        Map.entry(Blocks.PUMPKIN_STEM, Items.PUMPKIN_SEEDS),
        Map.entry(Blocks.REDSTONE_WIRE, Items.REDSTONE),
        Map.entry(Blocks.TRIPWIRE, Items.STRING)
    );

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgExtra = settings.createGroup("Extras");
    private final SettingGroup sgUi = settings.createGroup("Giao diện / UI");

    // stored path of the chosen file (set through the file dialog, no need to type it)
    private final Setting<String> file = sg.add(new StringSetting.Builder()
        .name("file").description("Đường dẫn file .litematic đã chọn (dùng nút Pick file / menu để chọn, không cần gõ).")
        .defaultValue("").visible(() -> false).build());

    private final Setting<Boolean> pickBtn = sg.add(new BoolSetting.Builder()
        .name("pick-file").description("Bật để mở hộp thoại chọn file .litematic từ máy của bạn.")
        .defaultValue(false)
        .onChanged(v -> { if (v) { this.pickBtn.set(false); mc.execute(this::pickFile); } })
        .build());

    private final Setting<Boolean> openMenuBtn = sg.add(new BoolSetting.Builder()
        .name("open-menu").description("Bật để mở menu AutoBuild.")
        .defaultValue(false)
        .onChanged(v -> { if (v) { this.openMenuBtn.set(false); mc.execute(this::openMenu); } })
        .build());

    private final Setting<Integer> forwardOffset = sg.add(new IntSetting.Builder()
        .name("forward-offset").description("The schematic's corner starts this many blocks in front of you.")
        .defaultValue(2).min(0).sliderMax(20).build());

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("Max distance to place or break a block.")
        .defaultValue(4.5).min(1).sliderMax(5).build());

    private final Setting<Integer> blocksPerTick = sg.add(new IntSetting.Builder()
        .name("blocks-per-action").description("Plain blocks placed per action (special blocks are placed one at a time).")
        .defaultValue(1).min(1).sliderMax(4).build());

    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay").description("Ticks between actions.")
        .defaultValue(3).min(0).sliderMax(20).build());

    private final Setting<Boolean> notify = sg.add(new BoolSetting.Builder()
        .name("notifications").defaultValue(true).build());

    public final Setting<Boolean> autoStart = sg.add(new BoolSetting.Builder()
        .name("auto-start").description("Tự bắt đầu xây ngay sau khi nạp file (tắt = chờ bấm Bắt đầu / phím P).")
        .defaultValue(false).build());

    public final Setting<Boolean> useScaffold = sgExtra.add(new BoolSetting.Builder()
        .name("use-scaffold").description("Place a temporary block next to floating blocks, then break it.")
        .defaultValue(true).build());

    private final Setting<Block> scaffoldBlock = sgExtra.add(new BlockSetting.Builder()
        .name("scaffold-block").description("Block used as temporary support (must be in your hotbar; pick one you can break quickly).")
        .defaultValue(Blocks.COBBLESTONE).build());

    public final Setting<Boolean> fixStates = sgExtra.add(new BoolSetting.Builder()
        .name("fix-states").description("Click repeaters, comparators, doors, trapdoors, gates and levers into the right state.")
        .defaultValue(true).build());

    public final Setting<Boolean> strictLayers = sgExtra.add(new BoolSetting.Builder()
        .name("strict-layers").description("Xây hết một tầng Y rồi mới lên tầng tiếp theo.")
        .defaultValue(true).build());

    public final Setting<Boolean> hud = sgUi.add(new BoolSetting.Builder()
        .name("hud").description("Hiện bảng tiến độ trên màn hình.").defaultValue(true).build());

    public final Setting<Boolean> vietnamese = sgUi.add(new BoolSetting.Builder()
        .name("vietnamese").description("Ngôn ngữ: bật = Tiếng Việt, tắt = English.").defaultValue(true).build());

    private final Setting<Keybind> menuKey = sgUi.add(new KeybindSetting.Builder()
        .name("menu-key").description("Mở menu (hoạt động khi module đang bật).")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_J)).action(this::openMenu).build());

    private final Setting<Keybind> runKey = sgUi.add(new KeybindSetting.Builder()
        .name("run-pause-key").description("Chạy / tạm dừng (hoạt động khi module đang bật).")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_P)).action(this::toggleRun).build());

    private final List<Target> pending = new ArrayList<>();
    private final Set<BlockPos> targetSet = new HashSet<>();
    private final Set<BlockPos> unmatched = new HashSet<>();
    private final Map<BlockPos, BlockPos> scaffolds = new LinkedHashMap<>(); // scaffold pos -> target it supports
    private final List<Fix> fixes = new ArrayList<>();
    private final Map<Integer, Integer> layerTotals = new HashMap<>();
    private int timer, warnCooldown, stall, skippedLoad, statTimer;
    private boolean lastHadNeighbor;

    // state shared with the menu / HUD
    private boolean running, finished, picking, forceClean;
    private String loadedName = "";
    private BlockPos anchor;
    private int totalCount;
    private Integer yMin, yMax; // absolute world Y, null = all

    // cached stats
    private boolean statHasLayer;
    private int statLayerY, statLayerDone, statLayerTotal;
    private int statNeed, statHave;
    private List<Need> statNeeds = List.of();

    public SchematicBuild() {
        super(DonutAddon.CATEGORY, "schematic-build", "Builds a .litematic file in front of you: correct orientation, scaffolding and clickable states.");
    }

    // ------------------------------------------------------------------ public API for the menu / HUD

    public String t(String vi, String en) { return vietnamese.get() ? vi : en; }

    public void setVietnamese(boolean v) { vietnamese.set(v); }

    public void openMenu() {
        mc.execute(() -> mc.setScreen(new BuildMenuScreen(GuiThemes.get(), this)));
    }

    public boolean isRunning() { return running; }
    public String loadedName() { return loadedName; }
    public int unmatchedCount() { return unmatched.size(); }
    public int scaffoldCount() { return scaffolds.size(); }
    public boolean isLoaded() { return totalCount > 0; }

    public String yMinText() { return yMin == null ? "" : String.valueOf(yMin); }
    public String yMaxText() { return yMax == null ? "" : String.valueOf(yMax); }
    public void setYMin(String s) { yMin = parseInt(s); }
    public void setYMax(String s) { yMax = parseInt(s); }

    private static Integer parseInt(String s) {
        try { return s == null || s.isBlank() ? null : Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private boolean inYRange(int y) { return (yMin == null || y >= yMin) && (yMax == null || y <= yMax); }

    public void toggleRun() {
        if (running) pause(); else start();
    }

    public void start() {
        if (mc.player == null) return;
        if (pending.isEmpty() && !finished) {
            if (file.get().isBlank()) { warning(t("Chưa chọn file schematic.", "No schematic selected.")); return; }
            loadFile(true);
            if (pending.isEmpty()) return;
        }
        if (pending.isEmpty() && finished) loadFile(false); // run again over the same spot
        if (!isActive()) toggle();
        running = true; finished = false; stall = 0;
    }

    public void pause() { running = false; }

    /** Re-read the file and re-scan the world, keeping the same build position. */
    public void restore() { if (!file.get().isBlank()) loadFile(false); }

    public void retryUnmatched() { unmatched.clear(); stall = 0; if (finished && !pending.isEmpty()) finished = false; }

    public void cleanScaffolds() { forceClean = true; }

    public String statusText() {
        if (totalCount == 0) return t("Chưa nạp schematic", "No schematic loaded");
        if (finished) return t("Hoàn thành", "Finished");
        if (running) return t("ĐANG XÂY", "BUILDING");
        return statDoneAll() == 0 ? t("Sẵn sàng", "Ready") : t("Tạm dừng", "Paused");
    }

    private int statDoneAll() { return Math.max(0, totalCount - pending.size()); }
    public int totalCount() { return totalCount; }
    public int doneCount() { return statDoneAll(); }
    public boolean hasLayer() { return statHasLayer; }
    public int layerY() { return statLayerY; }
    public int layerDone() { return statLayerDone; }
    public int layerTotal() { return statLayerTotal; }
    public int needSum() { return statNeed; }
    public int haveSum() { return statHave; }
    public List<Need> needs() { return statNeeds; }

    public static double pct(int a, int b) { return b <= 0 ? 0 : a * 100.0 / b; }

    // ------------------------------------------------------------------ file picking

    /** Opens the native file dialog on its own thread (it blocks), then loads the chosen file on the game thread. */
    public void pickFile() {
        if (picking) return;
        picking = true;
        String startDir = new File(mc.runDirectory, "schematics").getAbsolutePath() + File.separator;
        Thread th = new Thread(() -> {
            String res = null;
            Throwable err = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.litematic")).flip();
                res = TinyFileDialogs.tinyfd_openFileDialog("Chọn file .litematic / Select a .litematic file",
                    startDir, filters, "Litematica schematic (*.litematic)", false);
            } catch (Throwable e) {
                err = e;
            }
            String picked = res;
            Throwable failure = err;
            mc.execute(() -> {
                picking = false;
                if (failure != null) {
                    error(t("Không mở được hộp thoại chọn file (", "Could not open the file dialog (") + failure + t("). Hãy kéo-thả file vào menu.", "). Drag & drop the file onto the menu instead."));
                } else if (picked != null && !picked.isBlank()) {
                    selectFile(Paths.get(picked.split("\\|")[0]));
                }
            });
        }, "schematic-file-picker");
        th.setDaemon(true);
        th.start();
    }

    /** Called by the file dialog and by drag & drop. */
    public void selectFile(Path path) {
        if (path == null || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic")) {
            error(t("Chỉ hỗ trợ file .litematic.", "Only .litematic files are supported."));
            return;
        }
        file.set(path.toAbsolutePath().toString());
        loadFile(true);
    }

    // ------------------------------------------------------------------ loading

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        warnCooldown = 0; stall = 0; timer = 0;
        if (pending.isEmpty() && !file.get().isBlank()) {
            loadFile(true);
        } else if (pending.isEmpty()) {
            info(t("Chưa chọn file - mở menu để nạp schematic.", "No file selected - open the menu to load a schematic."));
            openMenu();
        }
    }

    @Override
    public void onDeactivate() {
        running = false;
        InvUtils.swapBack();
    }

    public void loadFile(boolean newAnchor) {
        if (mc.player == null) return;
        pending.clear(); targetSet.clear(); unmatched.clear(); fixes.clear(); layerTotals.clear();
        skippedLoad = 0; stall = 0; timer = 0; finished = false; running = false; totalCount = 0; loadedName = "";

        String raw = file.get().trim().replace("\"", "");
        Path path = raw.isEmpty() ? null : Paths.get(raw);
        if (path == null || !Files.isRegularFile(path)) {
            error(t("Không tìm thấy file: ", "File not found: ") + raw);
            return;
        }
        try {
            load(path, newAnchor);
        } catch (Exception e) {
            pending.clear(); targetSet.clear();
            error(t("Không đọc được schematic: ", "Could not read the schematic: ") + e);
            return;
        }
        totalCount = pending.size();
        for (Target t : pending) layerTotals.merge(t.pos().getY(), 1, Integer::sum);
        loadedName = path.getFileName().toString();
        recomputeStats();
        if (notify.get()) info(t("Đã nạp %d block cần đặt.", "Loaded %d blocks to place."), pending.size());
        if (autoStart.get() && !pending.isEmpty()) running = true;
    }

    @SuppressWarnings("unchecked")
    private void load(Path path, boolean newAnchor) throws IOException {
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (in.readUnsignedByte() != 10) throw new IOException("not an NBT file");
            in.readUTF();
            root = readCompound(in);
        }
        Map<String, Object> regions = (Map<String, Object>) root.get("Regions");
        if (regions == null) throw new IOException("no Regions tag (is this a .litematic file?)");

        List<Target> raw = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;

        for (Object o : regions.values()) {
            Map<String, Object> region = (Map<String, Object>) o;
            Map<String, Object> pos = (Map<String, Object>) region.get("Position");
            Map<String, Object> size = (Map<String, Object>) region.get("Size");
            int px = (Integer) pos.get("x"), py = (Integer) pos.get("y"), pz = (Integer) pos.get("z");
            int sx = (Integer) size.get("x"), sy = (Integer) size.get("y"), sz = (Integer) size.get("z");
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int rx = px + (sx < 0 ? sx + 1 : 0), ry = py + (sy < 0 ? sy + 1 : 0), rz = pz + (sz < 0 ? sz + 1 : 0);

            List<Object> palette = (List<Object>) region.get("BlockStatePalette");
            long[] states = (long[]) region.get("BlockStates");
            BlockState[] palStates = new BlockState[palette.size()];
            for (int i = 0; i < palStates.length; i++) {
                Map<String, Object> entry = (Map<String, Object>) palette.get(i);
                Identifier id = Identifier.tryParse((String) entry.get("Name"));
                Block b = id == null ? Blocks.AIR : Registries.BLOCK.get(id);
                palStates[i] = parseState(b, entry);
            }

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1;

            for (int y = 0; y < ay; y++) for (int z = 0; z < az; z++) for (int x = 0; x < ax; x++) {
                long idx = ((long) y * az + z) * ax + x;
                long bitPos = idx * bits;
                int li = (int) (bitPos >>> 6);
                int off = (int) (bitPos & 63);
                if (li >= states.length) continue;
                long v = states[li] >>> off;
                if (off + bits > 64 && li + 1 < states.length) v |= states[li + 1] << (64 - off);
                int pi = (int) (v & mask);
                if (pi >= palStates.length) continue;
                BlockState st = palStates[pi];
                if (st.isAir()) continue;
                if (skipAtLoad(st)) { skippedLoad++; continue; }

                int wx = rx + x, wy = ry + y, wz = rz + z;
                minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
                raw.add(new Target(new BlockPos(wx, wy, wz), st));
                if (raw.size() > 300_000) throw new IOException("schematic is too large (over 300000 blocks)");
            }
        }

        BlockPos origin;
        if (newAnchor || anchor == null) {
            Direction fwd = mc.player.getHorizontalFacing();
            origin = mc.player.getBlockPos().offset(fwd, forwardOffset.get());
            anchor = origin;
        } else {
            origin = anchor;
        }
        for (Target t : raw) {
            BlockPos p = origin.add(t.pos().getX() - minX, t.pos().getY() - minY, t.pos().getZ() - minZ);
            pending.add(new Target(p, t.state()));
        }
        pending.sort(Comparator.<Target>comparingInt(t -> t.pos().getY())
            .thenComparingInt(t -> t.pos().getZ()).thenComparingInt(t -> t.pos().getX()));
        for (Target t : pending) targetSet.add(t.pos());
    }

    /** Blocks that come for free with another block (upper door/plant half, bed head, piston head) or can't be placed (fluids). */
    private static boolean skipAtLoad(BlockState st) {
        Block b = st.getBlock();
        if (b instanceof PistonHeadBlock || b instanceof FluidBlock) return true;
        for (Property<?> p : st.getProperties()) {
            String n = p.getName();
            String v = String.valueOf(st.get(p));
            if (n.equals("half") && v.equals("upper") && (b instanceof DoorBlock || b instanceof TallPlantBlock)) return true;
            if (n.equals("part") && v.equals("head") && b instanceof BedBlock) return true;
        }
        return false;
    }

    private static BlockState parseState(Block b, Map<String, Object> entry) {
        BlockState s = b.getDefaultState();
        Object props = entry.get("Properties");
        if (props instanceof Map<?, ?> m) {
            for (Property<?> p : b.getStateManager().getProperties()) {
                Object v = m.get(p.getName());
                if (v instanceof String str) s = withProp(s, p, str);
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState withProp(BlockState s, Property<T> p, String v) {
        return p.parse(v).map(x -> s.with(p, x)).orElse(s);
    }

    // ------------------------------------------------------------------ minimal NBT reader

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> m = new HashMap<>();
        while (true) {
            int t = in.readUnsignedByte();
            if (t == 0) break;
            String name = in.readUTF();
            m.put(name, readTag(in, t));
        }
        return m;
    }

    private static Object readTag(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(readTag(in, t));
                return l;
            }
            case 10: return readCompound(in);
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    // ------------------------------------------------------------------ state helpers

    private static Item itemFor(BlockState s) {
        Item o = ITEM_OVERRIDE.get(s.getBlock());
        if (o != null) return o;
        Item i = s.getBlock().asItem();
        return i == Items.AIR ? null : i;
    }

    private static boolean hasProp(BlockState s, String name) {
        for (Property<?> p : s.getProperties()) if (p.getName().equals(name)) return true;
        return false;
    }

    private static boolean propDiffers(BlockState a, BlockState b, String name) {
        for (Property<?> p : a.getProperties()) {
            if (!p.getName().equals(name)) continue;
            if (!b.getProperties().contains(p)) return false;
            return !a.get(p).equals(b.get(p));
        }
        return false;
    }

    private static boolean isSlabType(BlockState s, Property<?> p) {
        return s.getBlock() instanceof SlabBlock && p.getName().equals("type");
    }

    private static boolean isDoubleSlab(BlockState s) {
        return s.getBlock() instanceof SlabBlock && s.get(SlabBlock.TYPE) == SlabType.DOUBLE;
    }

    private static boolean hasOrientation(BlockState want) {
        for (Property<?> p : want.getProperties()) {
            if (ORIENT.contains(p.getName())) return true;
            if (isSlabType(want, p) && want.get(SlabBlock.TYPE) != SlabType.DOUBLE) return true;
        }
        return false;
    }

    private static boolean matches(BlockState got, BlockState want) {
        for (Property<?> p : want.getProperties()) {
            boolean slab = isSlabType(want, p);
            if (!ORIENT.contains(p.getName()) && !slab) continue;
            if (slab && want.get(SlabBlock.TYPE) == SlabType.DOUBLE) continue;
            if (!got.getProperties().contains(p)) continue;
            if (!got.get(p).equals(want.get(p))) return false;
        }
        return true;
    }

    /** Does the block at the world need a click to reach the wanted state? */
    private boolean needsFix(BlockState want, BlockState cur) {
        if (cur.getBlock() != want.getBlock()) return false;
        Block b = want.getBlock();
        if (b instanceof RepeaterBlock && propDiffers(want, cur, "delay")) return true;
        if (b instanceof ComparatorBlock && propDiffers(want, cur, "mode")) return true;
        if ((b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock)
            && b != Blocks.IRON_DOOR && b != Blocks.IRON_TRAPDOOR && propDiffers(want, cur, "open")) return true;
        return b == Blocks.LEVER && propDiffers(want, cur, "powered");
    }

    private boolean hasSupport(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState s = mc.world.getBlockState(pos.offset(d));
            if (!s.isAir() && !s.isReplaceable()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ stats (menu + HUD)

    private void recomputeStats() {
        statHasLayer = false; statLayerDone = 0; statLayerTotal = 0; statNeed = 0; statHave = 0; statNeeds = List.of();
        if (mc.world == null || mc.player == null || pending.isEmpty()) return;

        int layer = 0;
        for (Target t : pending) {
            if (unmatched.contains(t.pos()) || !inYRange(t.pos().getY())) continue;
            layer = t.pos().getY();
            statHasLayer = true;
            break;
        }
        if (!statHasLayer) return;

        int left = 0;
        Map<Item, Integer> needs = new LinkedHashMap<>();
        for (Target t : pending) {
            int y = t.pos().getY();
            if (y < layer) continue;
            if (y > layer) break;
            left++;
            Item it = itemFor(t.state());
            if (it == null) continue;
            int n = 1;
            if (isDoubleSlab(t.state())) {
                BlockState c = mc.world.getBlockState(t.pos());
                n = c.getBlock() == t.state().getBlock() ? 1 : 2;
            }
            needs.merge(it, n, Integer::sum);
        }
        statLayerY = layer;
        statLayerTotal = layerTotals.getOrDefault(layer, left);
        statLayerDone = Math.max(0, statLayerTotal - left);

        List<Need> list = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : needs.entrySet()) {
            Item want = e.getKey();
            int have = InvUtils.find(s -> s.getItem() == want, 0, 8).count(); // sum of all 9 hotbar slots
            list.add(new Need(e.getKey().getName().getString(), e.getValue(), have));
            statNeed += e.getValue();
            statHave += Math.min(have, e.getValue());
        }
        statNeeds = list;
    }

    // ------------------------------------------------------------------ HUD

    @EventHandler
    private void onRender(Render2DEvent event) {
        if (!hud.get() || mc.player == null || mc.currentScreen != null) return;

        List<String> lines = new ArrayList<>();
        if (statHasLayer) {
            lines.add(String.format(Locale.ROOT, "Layer Y=%d: %d / %d · %.1f%%", statLayerY, statLayerDone, statLayerTotal, pct(statLayerDone, statLayerTotal)));
        }
        lines.add(String.format(Locale.ROOT, "%s: %d / %d · %.1f%%", t("Tổng", "Total"), statDoneAll(), totalCount, pct(statDoneAll(), totalCount)));
        lines.add(t("Trạng thái: ", "Status: ") + statusText());
        if (statHasLayer) {
            lines.add(String.format(Locale.ROOT, "%s · %s %d · Hotbar %d · %s %d", t("Vật liệu tầng", "Layer items"),
                t("Cần", "Required"), statNeed, statHave, t("Thiếu", "Missing"), statNeed - statHave));
            int shown = 0;
            for (Need n : statNeeds) {
                if (shown++ >= 6) { lines.add("..."); break; }
                lines.add(n.name() + ": " + n.have() + " / " + n.need() + (n.have() >= n.need() ? " ✓" : " ✗"));
            }
        }
        if (!unmatched.isEmpty()) lines.add(t("Block lỗi: ", "Bad blocks: ") + unmatched.size());
        if (!scaffolds.isEmpty()) lines.add(t("Support tạm: ", "Temp supports: ") + scaffolds.size());
        lines.add("[" + menuKey.get() + "] " + t("Menu", "Menu") + "  [" + runKey.get() + "] " + t("Chạy/tạm dừng", "Run/pause"));

        DrawContext ctx = event.drawContext;
        int w = 0;
        for (String s : lines) w = Math.max(w, mc.textRenderer.getWidth(s));
        int x = 6, y = 6, lh = mc.textRenderer.fontHeight + 2;
        ctx.fill(x - 4, y - 4, x + w + 4, y + lines.size() * lh + 2, 0xB0101820);
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(mc.textRenderer, lines.get(i), x, y + i * lh, i == lines.size() - 1 ? 0xFF8FA3B8 : 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ orientation search

    private Plan findPlan(BlockPos pos, BlockState want) {
        lastHadNeighbor = false;
        Block block = want.getBlock();
        Item item = itemFor(want);
        if (item == null) return null;
        ItemStack stack = new ItemStack(item);
        Vec3d eye = mc.player.getEyePos();
        float[] yaws = hasProp(want, "rotation") ? YAWS16 : YAWS4;
        boolean hinge = hasProp(want, "hinge");
        float oy = mc.player.getYaw(), op = mc.player.getPitch();
        try {
            for (Direction side : Direction.values()) {
                BlockPos nb = pos.offset(side.getOpposite());
                BlockState nbs = mc.world.getBlockState(nb);
                if (nbs.isAir() || nbs.isReplaceable()) continue;
                lastHadNeighbor = true;

                Vec3d face = Vec3d.ofCenter(nb).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
                List<Vec3d> hits = new ArrayList<>();
                if (side.getAxis().isHorizontal()) {
                    double[] lat = hinge ? new double[]{0.25, 0.75} : new double[]{0.5};
                    for (double l : lat) for (double yy : new double[]{0.25, 0.75}) {
                        double hx = side.getAxis() == Direction.Axis.X ? face.x : nb.getX() + l;
                        double hz = side.getAxis() == Direction.Axis.Z ? face.z : nb.getZ() + l;
                        hits.add(new Vec3d(hx, nb.getY() + yy, hz));
                    }
                } else if (hinge) {
                    for (double lx : new double[]{0.25, 0.75}) for (double lz : new double[]{0.25, 0.75})
                        hits.add(new Vec3d(nb.getX() + lx, face.y, nb.getZ() + lz));
                } else {
                    hits.add(face);
                }

                for (Vec3d hit : hits) {
                    if (eye.distanceTo(hit) > range.get()) continue;
                    BlockHitResult bhr = new BlockHitResult(hit, side, nb, false);
                    for (float pitch : PITCHES) for (float yaw : yaws) {
                        mc.player.setYaw(yaw);
                        mc.player.setPitch(pitch);
                        ItemPlacementContext ctx = new ItemPlacementContext(mc.player, Hand.MAIN_HAND, stack, bhr);
                        if (!ctx.canPlace() || !ctx.getBlockPos().equals(pos)) continue;
                        BlockState got = block.getPlacementState(ctx);
                        if (got != null && matches(got, want)) return new Plan(yaw, pitch, nb, side, hit);
                    }
                }
            }
        } finally {
            mc.player.setYaw(oy);
            mc.player.setPitch(op);
        }
        return null;
    }

    // ------------------------------------------------------------------ scaffolding

    private boolean tryScaffold(BlockPos target, Set<Block> missing) {
        if (!useScaffold.get()) return false;
        FindItemResult sc = InvUtils.findInHotbar(scaffoldBlock.get().asItem());
        if (!sc.found()) { missing.add(scaffoldBlock.get()); return false; }
        Vec3d eye = mc.player.getEyePos();
        BlockPos feet = mc.player.getBlockPos();
        for (Direction d : Direction.values()) {
            BlockPos c = target.offset(d);
            if (!mc.world.getBlockState(c).isReplaceable()) continue;
            if (targetSet.contains(c) || scaffolds.containsKey(c)) continue;
            if (c.equals(feet) || c.equals(feet.up())) continue;
            if (!hasSupport(c)) continue;
            if (eye.distanceTo(Vec3d.ofCenter(c)) > range.get()) continue;
            if (BlockUtils.place(c, sc, 50)) { scaffolds.put(c, target); return true; }
        }
        return false;
    }

    /** Breaks one scaffold whose target is finished. Returns true if it used this tick. */
    private boolean breakScaffolds() {
        if (scaffolds.isEmpty()) { forceClean = false; return false; }
        Vec3d eye = mc.player.getEyePos();
        Iterator<Map.Entry<BlockPos, BlockPos>> it = scaffolds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockPos> e = it.next();
            BlockPos sp = e.getKey(), served = e.getValue();
            BlockState cur = mc.world.getBlockState(sp);
            if (cur.isReplaceable()) { it.remove(); continue; }          // already gone
            if (!cur.isOf(scaffoldBlock.get())) { it.remove(); continue; } // not ours any more - leave it alone
            boolean servedDone = forceClean || !mc.world.getBlockState(served).isReplaceable() || unmatched.contains(served);
            if (!servedDone) continue;
            if (eye.distanceTo(Vec3d.ofCenter(sp)) > range.get()) continue;

            // best tool in the hotbar
            int bestSlot = -1;
            float bestSpeed = mc.player.getMainHandStack().getMiningSpeedMultiplier(cur);
            for (int i = 0; i < 9; i++) {
                float sp2 = mc.player.getInventory().getStack(i).getMiningSpeedMultiplier(cur);
                if (sp2 > bestSpeed + 0.01f) { bestSpeed = sp2; bestSlot = i; }
            }
            if (bestSlot >= 0) InvUtils.swap(bestSlot, true);

            if (!mc.interactionManager.isBreakingBlock()) mc.interactionManager.attackBlock(sp, Direction.UP);
            else mc.interactionManager.updateBlockBreakingProgress(sp, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        InvUtils.swapBack();
        return false;
    }

    // ------------------------------------------------------------------ state fixing

    private boolean doFix() {
        if (!fixStates.get() || fixes.isEmpty()) return false;
        Vec3d eye = mc.player.getEyePos();
        Iterator<Fix> it = fixes.iterator();
        while (it.hasNext()) {
            Fix f = it.next();
            BlockPos p = f.t.pos();
            if (!needsFix(f.t.state(), mc.world.getBlockState(p)) || f.attempts >= 8) { it.remove(); continue; }
            if (eye.distanceTo(Vec3d.ofCenter(p)) > range.get()) continue;
            f.attempts++;
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(p), Direction.UP, p, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ main loop

    private void finish(String extra) {
        running = false;
        finished = true;
        recomputeStats();
        if (notify.get()) {
            info(t("Hoàn thành schematic.", "Schematic finished."));
            if (extra != null) warning(extra);
            if (!unmatched.isEmpty()) warning(t("%d block bị bỏ qua (không có cách đặt đúng hướng hoặc không đặt được).", "%d blocks were skipped (no way to place them with that orientation, or not placeable)."), unmatched.size());
            if (skippedLoad > 0) info(t("%d block không cần thao tác (nửa trên cửa, đầu giường, chất lỏng...).", "%d blocks need no action (upper door halves, beds' heads, fluids...)."), skippedLoad);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (warnCooldown > 0) warnCooldown--;
        if (--statTimer <= 0) { statTimer = 10; recomputeStats(); }

        if (breakScaffolds()) return;   // breaking needs a call every tick
        if (!running) return;
        if (timer > 0) { timer--; return; }

        // ---- refresh the pending list
        Iterator<Target> pit = pending.iterator();
        while (pit.hasNext()) {
            Target t = pit.next();
            BlockState cur = mc.world.getBlockState(t.pos());
            if (isDoubleSlab(t.state())) {
                if (cur.getBlock() == t.state().getBlock() && cur.get(SlabBlock.TYPE) == SlabType.DOUBLE) { pit.remove(); continue; }
                if (!cur.isReplaceable() && cur.getBlock() != t.state().getBlock()) { pit.remove(); continue; }
                continue; // still needs its first or second half
            }
            if (cur.isReplaceable()) continue;       // still to place
            if (needsFix(t.state(), cur)) fixes.add(new Fix(t));
            pit.remove();                            // done or blocked by something else
        }

        boolean allSkipped = true;
        int layerY = 0;
        boolean haveLayer = false;
        for (Target t : pending) {
            if (!unmatched.contains(t.pos()) && inYRange(t.pos().getY())) {
                allSkipped = false;
                layerY = t.pos().getY();
                haveLayer = true;
                break;
            }
        }
        if (allSkipped && scaffolds.isEmpty() && (fixes.isEmpty() || !fixStates.get())) { finish(null); return; }

        // ---- place
        Vec3d eye = mc.player.getEyePos();
        int placed = 0;
        boolean specialDone = false;
        Set<Block> missing = new LinkedHashSet<>();

        for (Target t : pending) {
            if (placed >= blocksPerTick.get()) break;
            BlockPos pos = t.pos();
            if (unmatched.contains(pos)) continue;
            if (!inYRange(pos.getY())) continue;
            if (strictLayers.get() && haveLayer && pos.getY() != layerY) {
                if (pos.getY() > layerY) break;   // pending is sorted by Y
                continue;
            }
            if (eye.distanceTo(Vec3d.ofCenter(pos)) > range.get()) continue;

            BlockState want = t.state();
            BlockState cur = mc.world.getBlockState(pos);
            Item it = itemFor(want);
            if (it == null) { unmatched.add(pos); continue; }
            FindItemResult item = InvUtils.findInHotbar(it);
            if (!item.found()) { missing.add(want.getBlock()); continue; }

            // second half of a double slab: click the existing slab from above
            if (isDoubleSlab(want) && cur.getBlock() == want.getBlock()) {
                if (specialDone) continue;
                if (cur.get(SlabBlock.TYPE) != SlabType.DOUBLE) {
                    if (!InvUtils.swap(item.slot(), true)) continue;
                    Vec3d top = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    Rotations.rotate(mc.player.getYaw(), 90, () -> {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(top, Direction.UP, pos, false));
                        mc.player.swingHand(Hand.MAIN_HAND);
                        InvUtils.swapBack();
                    });
                    specialDone = true;
                    placed++;
                }
                continue;
            }

            BlockState planWant = isDoubleSlab(want) ? want.with(SlabBlock.TYPE, SlabType.BOTTOM) : want;

            // plain block
            if (!hasOrientation(planWant)) {
                if (!hasSupport(pos)) {
                    if (!specialDone && tryScaffold(pos, missing)) { specialDone = true; placed++; }
                    continue;
                }
                if (BlockUtils.place(pos, item, 50)) placed++;
                continue;
            }

            // oriented block
            if (specialDone) continue;
            Plan plan = findPlan(pos, planWant);
            if (plan == null) {
                if (lastHadNeighbor) unmatched.add(pos);               // neighbours exist but no way works
                else if (tryScaffold(pos, missing)) { specialDone = true; placed++; }
                continue;
            }
            if (!InvUtils.swap(item.slot(), true)) continue;
            Rotations.rotate(plan.yaw(), plan.pitch(), () -> {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(plan.hit(), plan.side(), plan.neighbor(), false));
                mc.player.swingHand(Hand.MAIN_HAND);
                InvUtils.swapBack();
            });
            specialDone = true;
            placed++;
        }

        boolean fixed = false;
        if (placed == 0) fixed = doFix();

        if (placed == 0 && !fixed) {
            stall++;
            if (stall > 150) {
                int left = 0;
                for (Target t : pending) if (!unmatched.contains(t.pos()) && inYRange(t.pos().getY())) left++;
                finish(left + t(" block chưa đặt được (ngoài tầm với, thiếu vật phẩm, hoặc không có chỗ bám).", " blocks could not be placed (out of reach, missing items, or nothing to attach to)."));
                return;
            }
            if (notify.get() && warnCooldown == 0) {
                if (!missing.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Block b : missing) { if (sb.length() > 0) sb.append(", "); sb.append(b.getName().getString()); }
                    warning(t("Thiếu trong hotbar: ", "Missing in hotbar: ") + sb);
                } else {
                    warning(t("Không có gì trong tầm với - hãy lại gần phần chưa xây.", "Nothing in range to place - move closer to the unfinished part."));
                }
                warnCooldown = 200;
            }
        } else {
            stall = 0;
        }
        timer = delay.get();
    }
}
