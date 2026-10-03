package com.example.addon.modules;

import com.example.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.Hand;

import java.util.List;
import java.util.Locale;

/**
 * Gear almost broken -> throw XP bottles from the inventory (needs Mending).
 * If you carry none, open /order, follow the menu path you configured, take XP bottles (up to 64) and use them.
 */
public class AutoRepair extends Module {
    private enum State { IDLE, WAIT_MENU, NAVIGATE, COOLDOWN }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgOrder = settings.createGroup("Order");

    private final Setting<Integer> threshold = sg.add(new IntSetting.Builder()
        .name("durability-percent").description("Repair when remaining durability is at or below this percent.")
        .defaultValue(10).min(1).max(90).sliderMax(50).build());

    private final Setting<Integer> stopAbove = sg.add(new IntSetting.Builder()
        .name("stop-above-percent").description("Stop throwing once every checked item is above this percent.")
        .defaultValue(60).min(2).max(100).sliderMax(100).build());

    private final Setting<Boolean> checkArmor = sg.add(new BoolSetting.Builder()
        .name("check-armor").defaultValue(true).build());

    private final Setting<Boolean> checkHands = sg.add(new BoolSetting.Builder()
        .name("check-hands").description("Check main hand and off hand.").defaultValue(true).build());

    private final Setting<Integer> hotbarSlot = sg.add(new IntSetting.Builder()
        .name("hotbar-slot").description("Hotbar slot (1-9) that XP bottles are moved to before throwing.")
        .defaultValue(9).min(1).max(9).sliderMin(1).sliderMax(9).build());

    private final Setting<Integer> throwDelay = sg.add(new IntSetting.Builder()
        .name("throw-delay").description("Ticks between bottle throws.")
        .defaultValue(4).min(1).sliderMax(20).build());

    private final Setting<Boolean> notify = sg.add(new BoolSetting.Builder()
        .name("notifications").defaultValue(true).build());

    private final Setting<Boolean> useOrder = sgOrder.add(new BoolSetting.Builder()
        .name("take-from-order").description("When out of XP bottles, fetch them from the order menu.")
        .defaultValue(true).build());

    private final Setting<String> orderCommand = sgOrder.add(new StringSetting.Builder()
        .name("order-command").defaultValue("/order").build());

    private final Setting<List<String>> orderPath = sgOrder.add(new StringListSetting.Builder()
        .name("order-path").description("Names of the menu items to click, in order, to reach the screen that holds your XP bottles. Leave empty if they are on the first screen.")
        .defaultValue(List.of()).build());

    private final Setting<Integer> takeAmount = sgOrder.add(new IntSetting.Builder()
        .name("take-amount").description("Stop taking once you hold at least this many XP bottles.")
        .defaultValue(64).min(1).sliderMax(256).build());

    private final Setting<Integer> maxTakes = sgOrder.add(new IntSetting.Builder()
        .name("max-takes").description("Safety limit of take-clicks per visit.")
        .defaultValue(6).min(1).sliderMax(30).build());

    private final Setting<Integer> maxVisits = sgOrder.add(new IntSetting.Builder()
        .name("max-visits").description("Max order-menu visits in a row while gear is still low.")
        .defaultValue(2).min(1).sliderMax(10).build());

    private final Setting<Integer> actionDelay = sgOrder.add(new IntSetting.Builder()
        .name("action-delay").description("Ticks between menu clicks.")
        .defaultValue(5).min(1).sliderMax(20).build());

    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private State state = State.IDLE;
    private int timer, timeout, actions, takes, pathIndex, misses, visits, warnCooldown;
    private boolean repairing;

    public AutoRepair() {
        super(DonutAddon.CATEGORY, "auto-repair", "Throws XP bottles (fetched from /order if needed) when gear is almost broken.");
    }

    @Override
    public void onActivate() { state = State.IDLE; timer = 0; repairing = false; visits = 0; warnCooldown = 0; }

    // ------------------------------------------------------------------ helpers

    private double percentLeft(ItemStack s) {
        if (s.isEmpty() || !s.isDamageable()) return 100;
        int max = s.getMaxDamage();
        if (max <= 0) return 100;
        return (max - s.getDamage()) * 100.0 / max;
    }

    private double lowestPercent() {
        double min = 100;
        if (checkArmor.get()) for (EquipmentSlot slot : ARMOR) min = Math.min(min, percentLeft(mc.player.getEquippedStack(slot)));
        if (checkHands.get()) {
            min = Math.min(min, percentLeft(mc.player.getEquippedStack(EquipmentSlot.MAINHAND)));
            min = Math.min(min, percentLeft(mc.player.getEquippedStack(EquipmentSlot.OFFHAND)));
        }
        return min;
    }

    private int countBottles() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.EXPERIENCE_BOTTLE)) n += s.getCount();
        }
        return n;
    }

    private void abort(String why) {
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        if (notify.get() && why != null) warning(why);
        state = State.COOLDOWN;
        timer = 400;
    }

    private void throwBottle(FindItemResult bottle) {
        if (!bottle.isHotbar()) {
            InvUtils.move().from(bottle.slot()).toHotbar(hotbarSlot.get() - 1);
            timer = 3;
            return;
        }
        if (!InvUtils.swap(bottle.slot(), true)) return;
        Rotations.rotate(mc.player.getYaw(), 90, () -> {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        });
        timer = throwDelay.get();
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (warnCooldown > 0) warnCooldown--;
        if (timer > 0) { timer--; return; }

        switch (state) {
            case IDLE -> {
                double lowest = lowestPercent();
                if (!repairing) {
                    if (lowest > threshold.get()) { visits = 0; return; }
                    repairing = true;
                } else if (lowest > stopAbove.get()) {
                    repairing = false; visits = 0;
                    if (notify.get()) info("Gear repaired.");
                    return;
                }
                if (mc.currentScreen != null) return;

                FindItemResult bottle = InvUtils.find(Items.EXPERIENCE_BOTTLE);
                if (bottle.found()) { throwBottle(bottle); return; }

                if (!useOrder.get() || visits >= maxVisits.get()) {
                    if (notify.get() && warnCooldown == 0) {
                        warning("Gear almost broken but no XP bottles in your inventory.");
                        warnCooldown = 600;
                    }
                    return;
                }
                visits++;
                String cmd = orderCommand.get();
                ChatUtils.sendPlayerMsg(cmd.startsWith("/") ? cmd : "/" + cmd);
                if (notify.get()) info("Out of XP bottles - opening the order menu.");
                timeout = 100; actions = 0; takes = 0; pathIndex = 0; misses = 0;
                state = State.WAIT_MENU;
            }
            case WAIT_MENU -> {
                if (mc.currentScreen instanceof GenericContainerScreen) { state = State.NAVIGATE; timer = actionDelay.get(); return; }
                if (--timeout <= 0) abort("Order menu did not open.");
            }
            case NAVIGATE -> {
                if (++actions > 60) { abort("Could not get XP bottles (too many steps)."); return; }
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    if (--timeout <= 0) abort("Order menu closed unexpectedly.");
                    return;
                }
                GenericContainerScreenHandler h = screen.getScreenHandler();
                int size = h.getRows() * 9;

                // 1) follow the configured menu path, one item per step
                List<String> path = orderPath.get();
                if (pathIndex < path.size()) {
                    String kw = path.get(pathIndex).toLowerCase(Locale.ROOT);
                    for (int i = 0; i < size; i++) {
                        ItemStack s = h.slots.get(i).getStack();
                        if (s.isEmpty()) continue;
                        if (s.getName().getString().toLowerCase(Locale.ROOT).contains(kw)) {
                            InvUtils.click().slotId(i);
                            pathIndex++;
                            misses = 0;
                            timer = actionDelay.get() + 5; // let the next screen load
                            return;
                        }
                    }
                    if (++misses > 10) abort("Menu item \"" + path.get(pathIndex) + "\" not found.");
                    timer = actionDelay.get();
                    return;
                }

                // 2) take XP bottles until we hold enough
                if (countBottles() >= takeAmount.get() || takes >= maxTakes.get()) {
                    mc.player.closeHandledScreen();
                    if (notify.get()) info("Took XP bottles (%d in inventory).", countBottles());
                    state = State.COOLDOWN; timer = 10;
                    return;
                }
                for (int i = 0; i < size; i++) {
                    if (h.slots.get(i).getStack().isOf(Items.EXPERIENCE_BOTTLE)) {
                        InvUtils.shiftClick().slotId(i);
                        takes++;
                        timer = actionDelay.get();
                        return;
                    }
                }
                if (++misses > 10) abort("No XP bottles found in that menu.");
                timer = actionDelay.get();
            }
            case COOLDOWN -> state = State.IDLE;
        }
    }
}
