package com.example.addon.modules;

import com.example.addon.AddonMain;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IMinecraft;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class BackgroundAutoClicker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> rightClick = sgGeneral.add(new BoolSetting.Builder()
        .name("right-click")
        .description("Enable automatic right-clicking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rightClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("right-click-delay")
        .description("Delay between right clicks in ticks.")
        .defaultValue(2)
        .min(0)
        .sliderMax(60)
        .visible(rightClick::get)
        .build()
    );

    private final Setting<Boolean> leftClick = sgGeneral.add(new BoolSetting.Builder()
        .name("left-click")
        .description("Enable automatic left-clicking.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> leftClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("left-click-delay")
        .description("Delay between left clicks in ticks.")
        .defaultValue(2)
        .min(0)
        .sliderMax(60)
        .visible(leftClick::get)
        .build()
    );

    private final Setting<Boolean> workInScreens = sgGeneral.add(new BoolSetting.Builder()
        .name("work-in-screens")
        .description("Continue clicking even while Escape menu, inventory, or overlay screens are open.")
        .defaultValue(true)
        .build()
    );

    private int rightTimer = 0;
    private int leftTimer = 0;

    public BackgroundAutoClicker() {
        super(AddonMain.ADDON_CATEGORY, "background-auto-clicker", "Automatically clicks even when unfocused or when escape/pause screens are open.");
    }

    @Override
    public void onActivate() {
        rightTimer = 0;
        leftTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        // Check screen restriction
        if (!workInScreens.get() && mc.screen != null) return;

        // Execute Right Click
        if (rightClick.get()) {
            rightTimer++;
            if (rightTimer >= rightClickDelay.get()) {
                performRightClick();
                rightTimer = 0;
            }
        }

        // Execute Left Click
        if (leftClick.get()) {
            leftTimer++;
            if (leftTimer >= leftClickDelay.get()) {
                performLeftClick();
                leftTimer = 0;
            }
        }
    }

    private void performRightClick() {
        if (mc.gameMode == null || mc.player == null) return;

        // Trigger Meteor internal right-click flag
        ((IMinecraft) mc).meteor$rightClick();

        // Direct interaction for background/screen execution
        if (mc.screen != null) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }

    private void performLeftClick() {
        if (mc.gameMode == null || mc.player == null) return;

        // Trigger left-click swing
        Utils.leftClick();

        // If screen is open, execute direct attack if aiming at an entity
        if (mc.screen != null && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) mc.hitResult;
            mc.gameMode.attack(mc.player, entityHit.getEntity());
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
