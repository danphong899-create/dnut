package com.example.addon.modules;

import com.example.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1) Opens nearby chests/barrels and takes the target item.
 * 2) Runs /order, picks the order for the target item with the HIGHEST price (read from the lore),
 *    delivers the items and confirms.
 */
public class AutoOrder extends Module {
    private enum State {
        IDLE, WAIT_CHEST, LOOT, WAIT_ORDER_MENU, WAIT_DELIVER_MENU, DELIVER, WAIT_CONFIRM, COOLDOWN
    }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgChest = settings.createGroup("Chests");

    private final Setting<Item> target = sg.add(new ItemSetting.Builder()
        .name("item").description("Item to take and deliver.")
        .defaultValue(Items.COBBLESTONE).build());

    private final Setting<Integer> minAmount = sg.add(new IntSetting.Builder()
        .name("min-amount").description("Start ordering once you hold at least this many items.")
        .defaultValue(64).min(1).sliderMax(2304).build());

    private final Setting<String> command = sg.add(new StringSetting.Builder()
        .name("command").description("Command that opens the orders menu.")
        .defaultValue("/order").build());

    private final Setting<String> priceKeyword = sg.add(new StringSetting.Builder()
        .name("price-keyword").description("Only read prices on lore lines containing this text (empty = any line with a $ price).")
        .defaultValue("").build());

    private final Setting<Integer> actionDelay = sg.add(new IntSetting.Builder()
        .name("action-delay").description("Ticks between actions.")
        .defaultValue(3).min(0).sliderMax(20).build());

    private final Setting<Integer> cooldown = sg.add(new IntSetting.Builder()
        .name("cooldown").description("Ticks to wait between cycles.")
        .defaultValue(60).min(10).sliderMax(1200).build());

    private final Setting<Boolean> notify = sg.add(new BoolSetting.Builder()
        .name("notifications").description("Chat feedback.").defaultValue(true).build());

    private final Setting<Boolean> useChests = sgChest.add(new BoolSetting.Builder()
        .name("take-from-chests").description("Open nearby chests/barrels and take the item.")
        .defaultValue(true).build());

    private final Setting<Double> reach = sgChest.add(new DoubleSetting.Builder()
        .name("reach").description("Max distance to a chest.")
        .defaultValue(4.0).min(1).sliderMax(5).build());

    private State state = State.IDLE;
    private int timer, timeout;
    private BlockPos currentChest;
    private final Set<BlockPos> looted = new HashSet<>();
    private int orderSyncId = -1;

    public AutoOrder() {
        super(DonutAddon.CATEGORY, "auto-order", "Takes items from chests and fills the highest-paying order.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE; timer = 0; timeout = 0; currentChest = null; looted.clear(); orderSyncId = -1;
    }

    // ------------------------------------------------------------------ helpers

    private int countTarget() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(target.get())) n += s.getCount();
        }
        return n;
    }

    private BlockPos findChest() {
        BlockPos base = mc.player.getBlockPos();
        int r = (int) Math.ceil(reach.get());
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            BlockPos p = base.add(x, y, z);
            if (looted.contains(p)) continue;
            Block b = mc.world.getBlockState(p).getBlock();
            if (!(b instanceof ChestBlock) && !(b instanceof BarrelBlock)) continue;
            double d = eye.distanceTo(Vec3d.ofCenter(p));
            if (d <= reach.get() && d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    /** Reads the first "$price" (supports K/M/B) from the stack's lore. Returns -1 if none found. */
    private double readPrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        Pattern p = Pattern.compile("\\$\\s?([0-9][0-9,]*\\.?[0-9]*)\\s?([kKmMbB])?");
        String kw = priceKeyword.get().toLowerCase(Locale.ROOT);
        for (Text line : lore.lines()) {
            String s = line.getString();
            if (!kw.isEmpty() && !s.toLowerCase(Locale.ROOT).contains(kw)) continue;
            Matcher m = p.matcher(s);
            if (m.find()) {
                try {
                    double v = Double.parseDouble(m.group(1).replace(",", ""));
                    String suf = m.group(2);
                    if (suf != null) switch (suf.toLowerCase(Locale.ROOT)) {
                        case "k" -> v *= 1_000;
                        case "m" -> v *= 1_000_000;
                        case "b" -> v *= 1_000_000_000;
                    }
                    return v;
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private void next(State s, int wait) { state = s; timer = wait; }

    private void finishCycle(String msg) {
        if (notify.get() && msg != null) info(msg);
        next(State.COOLDOWN, cooldown.get());
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (timer > 0) { timer--; return; }

        switch (state) {
            case IDLE -> {
                if (mc.currentScreen != null) return;
                if (countTarget() >= minAmount.get()) {
                    ChatUtils.sendPlayerMsg(command.get().startsWith("/") ? command.get() : "/" + command.get());
                    timeout = 100;
                    next(State.WAIT_ORDER_MENU, 10);
                    return;
                }
                if (!useChests.get()) { timer = 40; return; }
                BlockPos chest = findChest();
                if (chest == null) { timer = 40; return; }
                currentChest = chest;
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(chest), Direction.UP, chest, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                timeout = 40;
                next(State.WAIT_CHEST, 2);
            }
            case WAIT_CHEST -> {
                if (mc.currentScreen instanceof GenericContainerScreen) { next(State.LOOT, actionDelay.get()); return; }
                if (--timeout <= 0) { if (currentChest != null) looted.add(currentChest); next(State.IDLE, 5); }
            }
            case LOOT -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) { next(State.IDLE, 5); return; }
                GenericContainerScreenHandler h = screen.getScreenHandler();
                int size = h.getRows() * 9;
                for (int i = 0; i < size; i++) {
                    if (h.slots.get(i).getStack().isOf(target.get())) {
                        InvUtils.shiftClick().slotId(i);
                        timer = actionDelay.get();
                        return;
                    }
                }
                if (currentChest != null) looted.add(currentChest);
                mc.player.closeHandledScreen();
                next(State.IDLE, 5);
            }
            case WAIT_ORDER_MENU -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    GenericContainerScreenHandler h = screen.getScreenHandler();
                    int size = h.getRows() * 9;
                    int bestSlot = -1;
                    double bestPrice = -1;
                    for (int i = 0; i < size; i++) {
                        ItemStack s = h.slots.get(i).getStack();
                        if (!s.isOf(target.get())) continue;
                        double price = readPrice(s);
                        if (price > bestPrice) { bestPrice = price; bestSlot = i; }
                    }
                    if (bestSlot < 0) { mc.player.closeHandledScreen(); finishCycle("No order found for that item."); return; }
                    orderSyncId = h.syncId;
                    InvUtils.click().slotId(bestSlot);
                    if (notify.get()) info("Picked order in slot %d (price %.0f).", bestSlot, bestPrice);
                    timeout = 60;
                    next(State.WAIT_DELIVER_MENU, actionDelay.get() + 5);
                    return;
                }
                if (--timeout <= 0) finishCycle(null);
            }
            case WAIT_DELIVER_MENU -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen
                    && screen.getScreenHandler().syncId != orderSyncId) {
                    next(State.DELIVER, actionDelay.get());
                    return;
                }
                if (--timeout <= 0) { if (mc.currentScreen != null) mc.player.closeHandledScreen(); finishCycle(null); }
            }
            case DELIVER -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) { next(State.WAIT_CONFIRM, 5); timeout = 60; return; }
                GenericContainerScreenHandler h = screen.getScreenHandler();
                int size = h.getRows() * 9;
                for (int i = size; i < h.slots.size(); i++) {
                    if (h.slots.get(i).getStack().isOf(target.get())) {
                        InvUtils.shiftClick().slotId(i);
                        timer = actionDelay.get();
                        return;
                    }
                }
                mc.player.closeHandledScreen(); // closing triggers the confirm dialog
                timeout = 60;
                next(State.WAIT_CONFIRM, 5);
            }
            case WAIT_CONFIRM -> {
                Screen s = mc.currentScreen;
                if (s != null && !(s instanceof GenericContainerScreen) && clickConfirm(s)) {
                    finishCycle("Order delivered.");
                    return;
                }
                if (--timeout <= 0) finishCycle(null);
            }
            case COOLDOWN -> next(State.IDLE, 0);
        }
    }

    private boolean clickConfirm(Screen screen) {
        for (Element e : screen.children()) {
            if (!(e instanceof ClickableWidget w) || !w.active) continue;
            String t = w.getMessage().getString().toLowerCase(Locale.ROOT);
            if (t.contains("cancel") || t.contains("decline") || t.equals("no")) continue;
            if (t.contains("confirm") || t.contains("accept") || t.equals("yes")) {
                double x = w.getX() + w.getWidth() / 2.0, y = w.getY() + w.getHeight() / 2.0;
                screen.mouseClicked(new Click(x, y, new MouseInput(0, 0)), false);
                return true;
            }
        }
        return false;
    }
}
