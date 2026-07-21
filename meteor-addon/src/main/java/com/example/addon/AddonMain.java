package com.example.addon;

import com.example.addon.modules.BackgroundAutoClicker;
import com.example.addon.modules.HoldAutoLog;
import com.example.addon.modules.TitleAntiAFK;
import com.example.addon.modules.TitleDemoer;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.DisplayItemUtils;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddonMain extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("ComplexMCAddon");
    public static final Category ADDON_CATEGORY = new Category("ComplexMC", () -> DisplayItemUtils.toStack(Items.COMMAND_BLOCK));

    @Override
    public void onInitialize() {
        LOG.info("Initializing ComplexMC Bypasses Summer 2026 Addon!");

        // Register custom modules
        Modules.get().add(new HoldAutoLog());
        Modules.get().add(new TitleAntiAFK());
        Modules.get().add(new TitleDemoer());
        Modules.get().add(new BackgroundAutoClicker());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(ADDON_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
