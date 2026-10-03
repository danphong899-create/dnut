package com.example.addon.modules;

import com.example.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;

import java.util.Set;

/** Attacks selected MOBS in range. Players are never targeted. */
public class AutoAttackMob extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Set<EntityType<?>>> entities = sg.add(new EntityTypeListSetting.Builder()
        .name("entities").description("Mobs to attack.")
        .defaultValue(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER)
        .onlyAttackable().build());

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("Attack range.")
        .defaultValue(3.5).min(1).sliderMax(6).build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").description("Look at the target before attacking.")
        .defaultValue(true).build());

    private final Setting<Boolean> needLineOfSight = sg.add(new BoolSetting.Builder()
        .name("line-of-sight").description("Only attack mobs you can see.")
        .defaultValue(true).build());

    private final Setting<Boolean> waitCooldown = sg.add(new BoolSetting.Builder()
        .name("wait-cooldown").description("Only swing when the weapon is fully charged.")
        .defaultValue(true).build());

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;
        if (waitCooldown.get() && mc.player.getAttackCooldownProgress(0.5f) < 1f) return;

        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e instanceof PlayerEntity) continue; // mobs only
            if (!le.isAlive() || le == mc.player) continue;
            if (!entities.get().contains(e.getType())) continue;
            double d = mc.player.distanceTo(e);
            if (d > range.get() || d >= bestDist) continue;
            if (needLineOfSight.get() && !mc.player.canSee(e)) continue;
            best = e;
            bestDist = d;
        }
        if (best == null) return;

        final Entity target = best;
        Runnable hit = () -> {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        };
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), hit);
        else hit.run();
    }
}
