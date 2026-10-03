package com.example.addon.modules;

import com.example.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;

import java.util.List;
import java.util.Locale;

/** Flow on DonutSMP: /sell -> container menu -> put items in -> close -> confirm dialog -> click Yes/Confirm. */
public class AutoSellDonut extends Module {
    private enum State { IDLE, WAIT_MENU, DEPOSIT, WAIT_CONFIRM, COOLDOWN }
    public enum Mode { Whitelist, Blacklist }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Sell only listed items, or everything except listed items.")
        .defaultValue(Mode.Whitelist).build());

    private final Setting<List<Item>> items = sg.add(new ItemListSetting.Builder()
        .name("items").description("Item list.")
        .defaultValue(Items.COBBLESTONE, Items.DIRT).build());

    private final Setting<Integer> actionDelay = sg.add(new IntSetting.Builder()
        .name("action-delay").description("Ticks between actions.")
        .defaultValue(2).min(0).sliderMax(20).build());

    private final Setting<Integer> cooldown = sg.add(new IntSetting.Builder()
        .name("cooldown").description("Ticks to wait before the next /sell.")
        .defaultValue(100).min(20).sliderMax(1200).build());

    private final Setting<Boolean> notify = sg.add(new BoolSetting.Builder()
        .name("notifications").description("Chat feedback.").defaultValue(false).build());

    private State state = State.IDLE;
    private int timer;
    private int timeout;

    public AutoSellDonut() {
        super(DonutAddon.CATEGORY, "auto-sell", "Automates /sell: deposits items and confirms the dialog.");
    }

    @Override
    public void onActivate() { state = State.IDLE; timer = 0; timeout = 0; }

    private boolean shouldSell(ItemStack s) {
        if (s.isEmpty()) return false;
        boolean listed = items.get().contains(s.getItem());
        return mode.get() == Mode.Whitelist ? listed : !listed;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (timer > 0) { timer--; return; }

        switch (state) {
            case IDLE -> {
                if (mc.currentScreen != null) return;
                boolean any = false;
                for (int i = 0; i < mc.player.getInventory().size(); i++) {
                    if (shouldSell(mc.player.getInventory().getStack(i))) { any = true; break; }
                }
                if (!any) { timer = 20; return; }
                ChatUtils.sendPlayerMsg("/sell");
                state = State.WAIT_MENU;
                timeout = 100;
            }
            case WAIT_MENU -> {
                if (mc.currentScreen instanceof GenericContainerScreen) { state = State.DEPOSIT; timer = actionDelay.get(); return; }
                if (--timeout <= 0) { state = State.COOLDOWN; timer = cooldown.get(); }
            }
            case DEPOSIT -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) { state = State.IDLE; return; }
                GenericContainerScreenHandler h = screen.getScreenHandler();
                int containerSize = h.getRows() * 9;

                boolean moved = false;
                for (int i = containerSize; i < h.slots.size(); i++) {
                    if (shouldSell(h.slots.get(i).getStack())) {
                        InvUtils.shiftClick().slotId(i);
                        moved = true;
                        break; // one stack per action
                    }
                }
                timer = actionDelay.get();
                if (!moved) {
                    mc.player.closeHandledScreen(); // closing the menu triggers the confirm dialog
                    state = State.WAIT_CONFIRM;
                    timeout = 60;
                }
            }
            case WAIT_CONFIRM -> {
                Screen s = mc.currentScreen;
                if (s != null && !(s instanceof GenericContainerScreen) && clickConfirm(s)) {
                    if (notify.get()) info("Sold.");
                    state = State.COOLDOWN;
                    timer = cooldown.get();
                    return;
                }
                if (--timeout <= 0) { state = State.COOLDOWN; timer = cooldown.get(); }
            }
            case COOLDOWN -> state = State.IDLE;
        }
    }

    /** Finds a Yes/Confirm button on the dialog screen and clicks it. */
    private boolean clickConfirm(Screen screen) {
        for (Element e : screen.children()) {
            if (!(e instanceof ClickableWidget w) || !w.active) continue;
            String t = w.getMessage().getString().toLowerCase(Locale.ROOT);
            if (t.contains("cancel") || t.contains("decline") || t.equals("no")) continue;
            if (t.contains("confirm") || t.contains("accept") || t.equals("yes") || t.contains("sell")) {
                double x = w.getX() + w.getWidth() / 2.0, y = w.getY() + w.getHeight() / 2.0;
                screen.mouseClicked(new Click(x, y, new MouseInput(0, 0)), false);
                return true;
            }
        }
        return false;
    }
}
