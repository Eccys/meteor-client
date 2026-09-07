/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AntiAFK extends Module {
    private final SettingGroup sgActions = settings.createGroup("Actions");
    private final SettingGroup sgCamera = settings.createGroup("Camera");
    private final SettingGroup sgMessages = settings.createGroup("Messages");

    // Actions

    private final Setting<Boolean> jump = sgActions.add(new BoolSetting.Builder()
        .name("jump")
        .description("Jump randomly.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgActions.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings your hand.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sneak = sgActions.add(new BoolSetting.Builder()
        .name("sneak")
        .description("Sneaks and unsneaks quickly.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> sneakTime = sgActions.add(new IntSetting.Builder()
        .name("sneak-time")
        .description("How many ticks to stay sneaked.")
        .defaultValue(5)
        .min(1)
        .sliderMin(1)
        .visible(sneak::get)
        .build()
    );

    private final Setting<Boolean> strafe = sgActions.add(new BoolSetting.Builder()
        .name("strafe")
        .description("Strafe right and left.")
        .defaultValue(false)
        .onChanged(_ -> {
            strafeTimer = 0;
            direction = false;

            if (isActive()) {
                mc.options.keyLeft.setDown(false);
                mc.options.keyRight.setDown(false);
            }
        })
        .build()
    );

    private final Setting<Boolean> spin = sgActions.add(new BoolSetting.Builder()
        .name("spin")
        .description("Spins the player in place. Ignored while look sweep is on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SpinMode> spinMode = sgActions.add(new EnumSetting.Builder<SpinMode>()
        .name("spin-mode")
        .description("The method of rotating.")
        .defaultValue(SpinMode.Server)
        .visible(spin::get)
        .build()
    );

    private final Setting<Integer> spinSpeed = sgActions.add(new IntSetting.Builder()
        .name("speed")
        .description("The speed to spin you.")
        .defaultValue(7)
        .visible(spin::get)
        .build()
    );

    private final Setting<Integer> pitch = sgActions.add(new IntSetting.Builder()
        .name("pitch")
        .description("The pitch to send to the server.")
        .defaultValue(0)
        .range(-90, 90)
        .sliderRange(-90, 90)
        .visible(() -> spin.get() && spinMode.get() == SpinMode.Server)
        .build()
    );

    private final Setting<Boolean> switchHotbar = sgActions.add(new BoolSetting.Builder()
        .name("switch-hotbar")
        .description("Randomly switches active hotbar slots to avoid AFK detection.")
        .defaultValue(false)
        .onChanged(val -> {
            if (!val) restoreSwappedItem();
        })
        .build()
    );

    private final Setting<Integer> switchDelay = sgActions.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("The delay between hotbar switches in seconds.")
        .defaultValue(5)
        .min(1)
        .sliderMax(30)
        .visible(switchHotbar::get)
        .build()
    );

    private final Setting<Boolean> randomHotbarSlot = sgActions.add(new BoolSetting.Builder()
        .name("random-slot")
        .description("Switches to a completely random slot instead of sequential.")
        .defaultValue(true)
        .visible(switchHotbar::get)
        .build()
    );

    private final Setting<Boolean> cycleInventory = sgActions.add(new BoolSetting.Builder()
        .name("cycle-inventory")
        .description("Cycles through the entire inventory instead of just the hotbar.")
        .defaultValue(true)
        .visible(switchHotbar::get)
        .onChanged(val -> {
            if (!val) restoreSwappedItem();
        })
        .build()
    );

    // Camera

    private final Setting<Boolean> lookSweep = sgCamera.add(new BoolSetting.Builder()
        .name("look-sweep")
        .description("Pans the camera back and forth between two look angles.")
        .defaultValue(false)
        .onChanged(_ -> lookTick = 0)
        .build()
    );

    private final Setting<Double> startYaw = sgCamera.add(new DoubleSetting.Builder()
        .name("start-yaw")
        .description("Yaw in degrees at the start of the sweep.")
        .defaultValue(-25)
        .sliderRange(-180, 180)
        .visible(lookSweep::get)
        .build()
    );

    private final Setting<Double> startPitch = sgCamera.add(new DoubleSetting.Builder()
        .name("start-pitch")
        .description("Pitch in degrees at the start of the sweep.")
        .defaultValue(0)
        .range(-90, 90)
        .sliderRange(-90, 90)
        .visible(lookSweep::get)
        .build()
    );

    private final Setting<Double> endYaw = sgCamera.add(new DoubleSetting.Builder()
        .name("end-yaw")
        .description("Yaw in degrees at the end of the sweep.")
        .defaultValue(25)
        .sliderRange(-180, 180)
        .visible(lookSweep::get)
        .build()
    );

    private final Setting<Double> endPitch = sgCamera.add(new DoubleSetting.Builder()
        .name("end-pitch")
        .description("Pitch in degrees at the end of the sweep.")
        .defaultValue(0)
        .range(-90, 90)
        .sliderRange(-90, 90)
        .visible(lookSweep::get)
        .build()
    );

    private final Setting<Double> lookDuration = sgCamera.add(new DoubleSetting.Builder()
        .name("duration")
        .description("Seconds to go from the start look to the end look. Then it reverses.")
        .defaultValue(4)
        .min(0.1)
        .sliderRange(0.5, 20)
        .visible(lookSweep::get)
        .build()
    );

    // Messages

    private final Setting<Boolean> sendMessages = sgMessages.add(new BoolSetting.Builder()
        .name("send-messages")
        .description("Sends messages to prevent getting kicked for AFK.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> randomMessage = sgMessages.add(new BoolSetting.Builder()
        .name("random")
        .description("Selects a random message from your message list.")
        .defaultValue(false)
        .visible(sendMessages::get)
        .build()
    );

    private final Setting<Integer> delay = sgMessages.add(new IntSetting.Builder()
        .name("delay")
        .description("The delay between specified messages in seconds.")
        .defaultValue(15)
        .min(0)
        .sliderMax(30)
        .visible(sendMessages::get)
        .build()
    );

    private final Setting<List<String>> messages = sgMessages.add(new StringListSetting.Builder()
        .name("messages")
        .description("The messages to choose from.")
        .defaultValue(
            "Meteor on top!",
            "Meteor on crack!"
        )
        .visible(sendMessages::get)
        .build()
    );

    public AntiAFK() {
        super(Categories.Player, "anti-afk", "Performs different actions to prevent getting kicked while AFK.");
    }

    private final Random random = new Random();
    private int messageTimer = 0;
    private int messageI = 0;
    private int sneakTimer = 0;
    private int strafeTimer = 0;
    private int hotbarTimer = 0;
    private int lastSwappedSlot = -1;
    private int swappedHotbarSlot = -1;
    private int inventoryCycleSlot = SlotUtils.MAIN_START;
    private boolean direction = false;
    private float lastYaw;
    private int lookTick = 0;

    private void restoreSwappedItem() {
        if (lastSwappedSlot != -1 && Utils.canUpdate() && mc.player != null) {
            if (mc.player.containerMenu instanceof InventoryMenu && swappedHotbarSlot != -1) {
                InvUtils.quickSwap().fromId(swappedHotbarSlot).to(lastSwappedSlot);
            }
            lastSwappedSlot = -1;
            swappedHotbarSlot = -1;
        }
    }

    @Override
    public void onActivate() {
        if (sendMessages.get() && messages.get().isEmpty()) {
            warning("Message list is empty, disabling messages...");
            sendMessages.set(false);
        }

        lastYaw = mc.player.getYRot();
        messageTimer = delay.get() * 20;
        hotbarTimer = switchDelay.get() * 20;
        lookTick = 0;
        lastSwappedSlot = -1;
        swappedHotbarSlot = -1;
        inventoryCycleSlot = SlotUtils.MAIN_START;
    }

    @Override
    public void onDeactivate() {
        if (strafe.get()) {
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
        }
        restoreSwappedItem();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        // Jump
        if (jump.get()) {
            if (mc.options.keyJump.isDown()) mc.options.keyJump.setDown(false);
            else if (random.nextInt(99) == 0) mc.options.keyJump.setDown(true);
        }

        // Swing
        if (swing.get() && random.nextInt(99) == 0) {
            mc.player.swing(mc.player.getUsedItemHand());
        }

        // Sneak
        if (sneak.get()) {
            if (sneakTimer++ >= sneakTime.get()) {
                mc.options.keyShift.setDown(false);
                if (random.nextInt(99) == 0) sneakTimer = 0; // Sneak after ~5 seconds
            } else mc.options.keyShift.setDown(true);
        }

        // Strafe
        if (strafe.get() && strafeTimer-- <= 0) {
            mc.options.keyLeft.setDown(!direction);
            mc.options.keyRight.setDown(direction);
            direction = !direction;
            strafeTimer = 20;
        }

        // Camera sweep (start look -> end look over duration, then back)
        if (lookSweep.get()) {
            int durationTicks = Math.max(1, (int) Math.round(lookDuration.get() * 20.0));
            int cycle = durationTicks * 2;
            int phase = lookTick;
            lookTick = (lookTick + 1) % cycle;
            float t = phase <= durationTicks
                ? phase / (float) durationTicks
                : (cycle - phase) / (float) durationTicks;

            float fromYaw = startYaw.get().floatValue();
            float toYaw = endYaw.get().floatValue();
            float yaw = fromYaw + Mth.wrapDegrees(toYaw - fromYaw) * t;
            float lookPitch = Mth.clamp(
                Mth.lerp(t, startPitch.get().floatValue(), endPitch.get().floatValue()),
                -90f,
                90f
            );

            mc.player.setYRot(yaw);
            mc.player.setXRot(lookPitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = lookPitch;
        } else if (spin.get()) {
            lastYaw += spinSpeed.get();
            switch (spinMode.get()) {
                case Client -> mc.player.setYRot(lastYaw);
                case Server -> Rotations.rotate(lastYaw, pitch.get(), -15);
            }
        }

        // Switch Hotbar / Inventory
        if (switchHotbar.get() && hotbarTimer-- <= 0) {
            boolean canSwapInventory = cycleInventory.get()
                && mc.player.containerMenu instanceof InventoryMenu
                && mc.player.containerMenu.getCarried().isEmpty();

            if (!cycleInventory.get()) {
                restoreSwappedItem();
                int currentSlot = mc.player.getInventory().getSelectedSlot();
                int nextSlot;

                if (randomHotbarSlot.get()) {
                    nextSlot = random.nextInt(8);
                    if (nextSlot >= currentSlot) nextSlot++;
                } else {
                    nextSlot = (currentSlot + 1) % 9;
                }

                InvUtils.swap(nextSlot, false);
            } else if (randomHotbarSlot.get()) {
                int currentHotbar = mc.player.getInventory().getSelectedSlot();
                int prevActive = (lastSwappedSlot != -1) ? lastSwappedSlot : currentHotbar;

                restoreSwappedItem();

                List<Integer> availableSlots = new ArrayList<>();
                for (int i = 0; i < 9; i++) {
                    availableSlots.add(i);
                }
                if (canSwapInventory) {
                    for (int i = SlotUtils.MAIN_START; i <= SlotUtils.MAIN_END; i++) {
                        if (!mc.player.getInventory().getItem(i).isEmpty()) {
                            availableSlots.add(i);
                        }
                    }
                }

                List<Integer> pickList = new ArrayList<>(availableSlots);
                pickList.remove(Integer.valueOf(prevActive));
                int chosen = pickList.isEmpty() ? availableSlots.get(0) : pickList.get(random.nextInt(pickList.size()));

                if (chosen < 9) {
                    InvUtils.swap(chosen, false);
                } else {
                    currentHotbar = mc.player.getInventory().getSelectedSlot();
                    InvUtils.quickSwap().fromId(currentHotbar).to(chosen);
                    lastSwappedSlot = chosen;
                    swappedHotbarSlot = currentHotbar;
                }
            } else {
                // Sequential mode
                if (lastSwappedSlot != -1) {
                    // Currently showing an inventory item
                    restoreSwappedItem();

                    int foundSlot = -1;
                    if (canSwapInventory) {
                        for (int i = inventoryCycleSlot; i <= SlotUtils.MAIN_END; i++) {
                            if (!mc.player.getInventory().getItem(i).isEmpty()) {
                                foundSlot = i;
                                break;
                            }
                        }
                    }

                    if (foundSlot != -1) {
                        int currentHotbar = mc.player.getInventory().getSelectedSlot();
                        InvUtils.quickSwap().fromId(currentHotbar).to(foundSlot);
                        lastSwappedSlot = foundSlot;
                        swappedHotbarSlot = currentHotbar;
                        inventoryCycleSlot = foundSlot + 1;
                    } else {
                        // Done with main inventory, loop back to hotbar slot 0
                        inventoryCycleSlot = SlotUtils.MAIN_START;
                        InvUtils.swap(0, false);
                    }
                } else {
                    // Currently in hotbar slots
                    int currentSlot = mc.player.getInventory().getSelectedSlot();
                    if (currentSlot < 8) {
                        InvUtils.swap(currentSlot + 1, false);
                    } else {
                        // Hotbar is done (was at slot 8)! Check main inventory
                        int foundSlot = -1;
                        if (canSwapInventory) {
                            for (int i = SlotUtils.MAIN_START; i <= SlotUtils.MAIN_END; i++) {
                                if (!mc.player.getInventory().getItem(i).isEmpty()) {
                                    foundSlot = i;
                                    break;
                                }
                            }
                        }

                        if (foundSlot != -1) {
                            InvUtils.quickSwap().fromId(currentSlot).to(foundSlot);
                            lastSwappedSlot = foundSlot;
                            swappedHotbarSlot = currentSlot;
                            inventoryCycleSlot = foundSlot + 1;
                        } else {
                            // Main inventory has no items, wrap back to hotbar slot 0
                            inventoryCycleSlot = SlotUtils.MAIN_START;
                            InvUtils.swap(0, false);
                        }
                    }
                }
            }

            hotbarTimer = switchDelay.get() * 20;
        }

        // Messages
        if (sendMessages.get() && !messages.get().isEmpty() && messageTimer-- <= 0) {
            if (randomMessage.get()) messageI = random.nextInt(messages.get().size());
            else if (++messageI >= messages.get().size()) messageI = 0;

            ChatUtils.sendPlayerMsg(messages.get().get(messageI));
            messageTimer = delay.get() * 20;
        }
    }

    public enum SpinMode {
        Server,
        Client
    }
}
