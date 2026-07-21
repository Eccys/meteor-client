package com.example.addon.modules;

import com.example.addon.AddonMain;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;

public class TitleDemoer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<DemoTitle> titleText = sgGeneral.add(new EnumSetting.Builder<DemoTitle>()
        .name("title-text")
        .description("The title text display to simulate.")
        .defaultValue(DemoTitle.Sneak)
        .build()
    );

    private final Setting<Integer> titleDurationSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("title-duration-seconds")
        .description("How long the simulated title stays active on screen in seconds.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("Interval in seconds between title triggers.")
        .defaultValue(5)
        .min(1)
        .sliderMax(30)
        .build()
    );

    private int timer = 0;

    public TitleDemoer() {
        super(AddonMain.ADDON_CATEGORY, "title-demoer", "Simulates title displays to easily test TitleAntiAFK.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        if (timer-- <= 0) {
            timer = interval.get() * 20;

            String displayStr = titleText.get().getText();
            Component component = Component.literal(displayStr);

            // Configure title animation times (10 ticks fade in, duration*20 stay, 10 ticks fade out)
            mc.gui.setTimes(10, titleDurationSeconds.get() * 20, 10);
            mc.gui.setTitle(component);

            // Direct trigger to TitleAntiAFK module if active
            TitleAntiAFK antiAfk = Modules.get().get(TitleAntiAFK.class);
            if (antiAfk != null && antiAfk.isActive()) {
                antiAfk.processTitleText(displayStr);
            }

            info("Triggered demo title: \"" + displayStr + "\" (Duration: " + titleDurationSeconds.get() + "s)");
        }
    }

    public enum DemoTitle {
        Sneak("sneak"),
        LookLeft("look left"),
        LookRight("look right"),
        Jump("jump");

        private final String text;

        DemoTitle(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }
}
