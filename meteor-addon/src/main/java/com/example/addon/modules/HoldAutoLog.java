package com.example.addon.modules;

import com.example.addon.AddonMain;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class HoldAutoLog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("The item that triggers an automatic logout when held.")
        .defaultValue(Items.TOTEM_OF_UNDYING)
        .build()
    );

    public HoldAutoLog() {
        super(AddonMain.ADDON_CATEGORY, "hold-auto-log", "Disconnects you automatically when holding a specific item.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        Item heldMain = mc.player.getMainHandItem().getItem();
        Item heldOff = mc.player.getOffhandItem().getItem();
        Item trigger = targetItem.get();

        if (heldMain == trigger || heldOff == trigger) {
            String itemName = trigger.getName(trigger.getDefaultInstance()).getString();
            info("Holding target item (" + itemName + "). Logging out and toggling off module.");

            // Disconnect first
            disconnect("Holding target item: " + itemName);

            // Turn off module so reconnecting does not auto-loop log out
            if (isActive()) {
                toggle();
            }
        }
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(
                Component.literal("[HoldAutoLog] ").append(reason)
            ));
        }
    }
}
