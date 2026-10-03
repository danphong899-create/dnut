
Autoominousbottle · JAVA
package com.example.addon.modules;
 
import com.example.addon.DonutAddon;
import com.example.addon.mixin.BossBarHudAccessor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
 
import java.util.Locale;
 
public class AutoOminousBottle extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
 
    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay").description("Ticks between attempts.")
        .defaultValue(20).min(1).sliderMax(100).build());
 
    private final Setting<Boolean> swapBack = sg.add(new BoolSetting.Builder()
        .name("swap-back").description("Return to the previous slot after drinking.")
        .defaultValue(true).build());
 
    private final Setting<Boolean> waitForRaidEnd = sg.add(new BoolSetting.Builder()
        .name("wait-for-raid-end").description("Only drink when no raid is active (detected from the Raid boss bar).")
        .defaultValue(true).build());
 
    private final Setting<Integer> afterRaidDelay = sg.add(new IntSetting.Builder()
        .name("after-raid-delay").description("Extra ticks to wait after the raid ends before drinking.")
        .defaultValue(40).min(0).sliderMax(400).visible(waitForRaidEnd::get).build());
 
    private final Setting<Boolean> notify = sg.add(new BoolSetting.Builder()
        .name("notifications").description("Chat feedback.").defaultValue(false).build());
 
    private int timer;
    private int sinceRaid = Integer.MAX_VALUE;
    private boolean wasRaid;
 
    public AutoOminousBottle() {
        super(DonutAddon.CATEGORY, "auto-ominous-bottle", "Drinks an Ominous Bottle when there is no Bad Omen and (optionally) no raid in progress.");
    }
 
    @Override
    public void onActivate() { timer = 0; sinceRaid = Integer.MAX_VALUE; wasRaid = false; }
 
    /** True while a raid boss bar ("Raid") is shown. Victory/Defeat bars count as finished. */
    private boolean raidActive() {
        var bars = ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).donut$getBossBars();
        for (ClientBossBar bar : bars.values()) {
            String name = bar.getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("raid") && !name.contains("victory") && !name.contains("defeat")) return true;
        }
        return false;
    }
 
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
 
        if (waitForRaidEnd.get()) {
            boolean raid = raidActive();
            if (raid) { wasRaid = true; sinceRaid = 0; }
            else if (sinceRaid < Integer.MAX_VALUE) {
                sinceRaid++;
                if (wasRaid && notify.get() && sinceRaid == 1) info("Raid ended.");
                wasRaid = false;
            }
            if (raid) return;
            if (sinceRaid < afterRaidDelay.get()) return;
        }
 
        if (timer > 0) { timer--; return; }
 
        // Bad Omen = ready to start a raid in a village; Raid Omen = raid about to begin.
        if (mc.player.hasStatusEffect(StatusEffects.BAD_OMEN)) return;
        if (mc.player.hasStatusEffect(StatusEffects.RAID_OMEN)) return;
 
        FindItemResult bottle = InvUtils.findInHotbar(Items.OMINOUS_BOTTLE);
        if (!bottle.found()) return;
 
        if (!InvUtils.swap(bottle.slot(), swapBack.get())) return;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (swapBack.get()) InvUtils.swapBack();
        if (notify.get()) info("Drank an Ominous Bottle.");
 
        timer = delay.get();
    }
}
 
