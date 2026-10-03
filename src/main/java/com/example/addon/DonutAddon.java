package com.example.addon;

import com.example.addon.modules.AutoAttackMob;
import com.example.addon.modules.AutoLogoutOnPlayer;
import com.example.addon.modules.AutoOminousBottle;
import com.example.addon.modules.AutoOrder;
import com.example.addon.modules.AutoRepair;
import com.example.addon.modules.AutoSellDonut;
import com.example.addon.modules.SchematicBuild;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class DonutAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Donut");

    @Override
    public void onInitialize() {
        LOG.info("Donut addon loaded");
        Modules.get().add(new AutoOminousBottle());
        Modules.get().add(new AutoSellDonut());
        Modules.get().add(new AutoLogoutOnPlayer());
        Modules.get().add(new AutoOrder());
        Modules.get().add(new AutoAttackMob());
        Modules.get().add(new AutoRepair());
        Modules.get().add(new SchematicBuild());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("danphong899-create", "addonfarm");
    }
}
