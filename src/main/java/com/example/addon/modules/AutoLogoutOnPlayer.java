package com.example.addon.modules;

import com.example.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class AutoLogoutOnPlayer extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("Max distance (0 = any loaded player).")
        .defaultValue(0).min(0).sliderMax(128).build());

    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("ignore-friends").defaultValue(true).build());

    private final Setting<List<String>> ignoreNames = sg.add(new StringListSetting.Builder()
        .name("ignore-names").description("Player names to ignore.")
        .defaultValue(new ArrayList<>()).build());

    private final Setting<Boolean> toggleOff = sg.add(new BoolSetting.Builder()
        .name("toggle-off").description("Disable this module after logging out.")
        .defaultValue(true).build());

    public AutoLogoutOnPlayer() {
        super(DonutAddon.CATEGORY, "auto-logout-player", "Disconnects when another player is detected.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            String name = p.getName().getString();
            if (ignoreFriends.get() && Friends.get().isFriend(p)) continue;
            if (ignoreNames.get().stream().anyMatch(n -> n.equalsIgnoreCase(name))) continue;
            if (range.get() > 0 && mc.player.distanceTo(p) > range.get()) continue;

            mc.getNetworkHandler().getConnection().disconnect(Text.literal("[Donut] Logged out: " + name + " detected."));
            if (toggleOff.get()) toggle();
            return;
        }
    }
}
