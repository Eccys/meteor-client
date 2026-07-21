package com.example.addon.modules;

import com.example.addon.AddonMain;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Random;

public class TitleAntiAFK extends Module {
    private static final float PI_FLOAT = (float) Math.PI;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBruteForce = settings.createGroup("Brute Force");

    private final Setting<Boolean> bruteForce = sgBruteForce.add(new BoolSetting.Builder()
        .name("brute-force")
        .description("Aggressively performs pixel-perfect smooth 180-degree turns and pitch up/down with 1-second cadence jumping, sneaking, and punching.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onAnyTitle = sgBruteForce.add(new BoolSetting.Builder()
        .name("on-any-title")
        .description("Triggers Brute Force mode whenever ANY title display pops up, regardless of text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> humanWobble = sgBruteForce.add(new BoolSetting.Builder()
        .name("human-micro-wobble")
        .description("Adds tiny 1-2 pixel organic human hand micro-variations to prevent straight-line camera paths.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> bruteForceDuration = sgBruteForce.add(new IntSetting.Builder()
        .name("brute-force-duration-seconds")
        .description("Duration in seconds for brute force anti-AFK execution.")
        .defaultValue(7)
        .min(1)
        .sliderMax(20)
        .visible(() -> bruteForce.get() || onAnyTitle.get())
        .build()
    );

    private final Setting<Integer> actionCadenceSeconds = sgBruteForce.add(new IntSetting.Builder()
        .name("action-cadence-seconds")
        .description("Interval in seconds for jump, punch, and sneak actions during brute force.")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .visible(() -> bruteForce.get() || onAnyTitle.get())
        .build()
    );

    private final Setting<Integer> titleHoldSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("title-active-seconds")
        .description("How long to keep executing actions while title display remains active.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    // Position & Rotation Memory
    private Vec3 originalPos;
    private float originalYaw;
    private float originalPitch;
    private boolean savedState = false;

    // Active title tracking & cached action flags
    private String activeTitleText = null;
    private int titleActiveTicks = 0;
    private boolean titleHasSneak = false;
    private boolean titleHasJump = false;
    private boolean titleHasLookLeft = false;
    private boolean titleHasLookRight = false;

    // Sequential Camera Rotation Steps — each step is at most 180° so no degree wrapping needed
    private enum LookStep {
        LOOK_LEFT,          // originalYaw -> originalYaw - 180  (left 180°)
        CENTER_FROM_LEFT,   // originalYaw - 180 -> originalYaw  (right 180° back to center)
        LOOK_RIGHT,         // originalYaw -> originalYaw + 180  (right 180°)
        CENTER_FROM_RIGHT,  // originalYaw + 180 -> originalYaw  (left 180° back to center)
        LOOK_UP,            // originalPitch -> -75               (pitch up)
        LOOK_DOWN,          // -75 -> +75                         (pitch down 150°)
        RESET_PITCH         // +75 -> originalPitch               (pitch back to origin)
    }

    private int bruteForceTotalTicks = 0;
    private LookStep currentLookStep = LookStep.LOOK_LEFT;

    // High-FPS frame-rate interpolation variables
    private float stepTimeElapsed = 0f;
    private float stepDurationSeconds = 1.2f;
    private int sneakPulseTicks = 0;

    private float startYaw = 0;
    private float targetYaw = 0;
    private float startPitch = 0;
    private float targetPitch = 0;

    private final Random random = new Random();

    public TitleAntiAFK() {
        super(AddonMain.ADDON_CATEGORY, "title-anti-afk", "Reads on-screen title displays, performing 144+ FPS humanized camera motion, 1-second action cadences, and state restoration.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        restoreStateIfSaved();
        resetState();
    }

    private void saveState() {
        if (!Utils.canUpdate() || savedState) return;
        originalPos = mc.player.position();
        originalYaw = mc.player.getYRot();
        originalPitch = mc.player.getXRot();
        savedState = true;
    }

    private void restoreStateIfSaved() {
        if (!Utils.canUpdate() || !savedState) return;
        mc.player.teleportTo(originalPos.x, originalPos.y, originalPos.z);
        mc.player.setYRot(originalYaw);
        mc.player.setXRot(originalPitch);
        mc.options.keyShift.setDown(false);
        mc.options.keyJump.setDown(false);
        savedState = false;
    }

    private void resetState() {
        activeTitleText = null;
        titleActiveTicks = 0;
        titleHasSneak = false;
        titleHasJump = false;
        titleHasLookLeft = false;
        titleHasLookRight = false;
        bruteForceTotalTicks = 0;
        stepTimeElapsed = 0f;
        sneakPulseTicks = 0;
        currentLookStep = LookStep.LOOK_LEFT;
        savedState = false;
        if (mc.options != null) {
            mc.options.keyShift.setDown(false);
            mc.options.keyJump.setDown(false);
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!Utils.canUpdate()) return;

        Component comp = null;
        if (event.packet instanceof ClientboundSetTitleTextPacket p) {
            comp = p.text();
        } else if (event.packet instanceof ClientboundSetSubtitleTextPacket p) {
            comp = p.text();
        } else if (event.packet instanceof ClientboundClearTitlesPacket) {
            if (bruteForceTotalTicks <= 0) {
                titleActiveTicks = 0;
                activeTitleText = null;
            }
            return;
        }

        if (comp != null) {
            String text = comp.getString().toLowerCase(Locale.ROOT).trim();
            if (!text.isEmpty()) {
                processTitleText(text);
            }
        }
    }

    public void processTitleText(String text) {
        if (text == null || text.isBlank()) return;

        if (bruteForceTotalTicks > 0) {
            info("Brute Force active: Ignored secondary title \"" + text + "\"");
            return;
        }

        saveState();
        activeTitleText = text;
        titleActiveTicks = titleHoldSeconds.get() * 20;

        titleHasSneak = text.contains("sneak");
        titleHasJump = text.contains("jump");
        titleHasLookLeft = text.contains("look left");
        titleHasLookRight = text.contains("look right");

        if (bruteForce.get() || onAnyTitle.get()) {
            bruteForceTotalTicks = bruteForceDuration.get() * 20;
            startBruteForceSequence();
            info("Brute force Anti-AFK triggered for title: \"" + text + "\"");
            return;
        }

        info("Title detected: \"" + text + "\" (Executing while active for " + titleHoldSeconds.get() + "s)");
        initActionForText(text);
    }

    private void startBruteForceSequence() {
        currentLookStep = LookStep.LOOK_LEFT;
        prepareLookStep(LookStep.LOOK_LEFT);
    }

    private void prepareLookStep(LookStep step) {
        currentLookStep = step;
        stepTimeElapsed = 0f;

        startYaw = mc.player.getYRot();
        startPitch = mc.player.getXRot();

        switch (step) {
            case LOOK_LEFT -> {
                // Turn left 180° from original position
                stepDurationSeconds = 1.2f;
                targetYaw = originalYaw - 180f;
                targetPitch = originalPitch;
            }
            case CENTER_FROM_LEFT -> {
                // Return to center from left (right 180°)
                stepDurationSeconds = 1.2f;
                targetYaw = originalYaw;
                targetPitch = originalPitch;
            }
            case LOOK_RIGHT -> {
                // Turn right 180° from original position
                stepDurationSeconds = 1.2f;
                targetYaw = originalYaw + 180f;
                targetPitch = originalPitch;
            }
            case CENTER_FROM_RIGHT -> {
                // Return to center from right (left 180°)
                stepDurationSeconds = 1.2f;
                targetYaw = originalYaw;
                targetPitch = originalPitch;
            }
            case LOOK_UP -> {
                // Pitch up to -75°
                stepDurationSeconds = 0.8f;
                targetYaw = originalYaw;
                targetPitch = -75f;
            }
            case LOOK_DOWN -> {
                // Pitch down to +75°
                stepDurationSeconds = 1.0f;
                targetYaw = originalYaw;
                targetPitch = 75f;
            }
            case RESET_PITCH -> {
                // Pitch back to original
                stepDurationSeconds = 0.6f;
                targetYaw = originalYaw;
                targetPitch = originalPitch;
            }
        }
    }

    private void advanceLookStep() {
        switch (currentLookStep) {
            case LOOK_LEFT -> prepareLookStep(LookStep.CENTER_FROM_LEFT);
            case CENTER_FROM_LEFT -> prepareLookStep(LookStep.LOOK_RIGHT);
            case LOOK_RIGHT -> prepareLookStep(LookStep.CENTER_FROM_RIGHT);
            case CENTER_FROM_RIGHT -> prepareLookStep(LookStep.LOOK_UP);
            case LOOK_UP -> prepareLookStep(LookStep.LOOK_DOWN);
            case LOOK_DOWN -> prepareLookStep(LookStep.RESET_PITCH);
            case RESET_PITCH -> prepareLookStep(LookStep.LOOK_LEFT); // Loop
        }
    }

    private void performPunch() {
        if (mc.player == null) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (mc.gameMode != null && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult hit = (EntityHitResult) mc.hitResult;
            mc.gameMode.attack(mc.player, hit.getEntity());
        }
    }

    private void initActionForText(String text) {
        if (titleHasLookLeft) {
            startYaw = mc.player.getYRot();
            targetYaw = startYaw - 180f;
            targetPitch = originalPitch;
            stepTimeElapsed = 0f;
            stepDurationSeconds = 0.8f;
        } else if (titleHasLookRight) {
            startYaw = mc.player.getYRot();
            targetYaw = startYaw + 180f;
            targetPitch = originalPitch;
            stepTimeElapsed = 0f;
            stepDurationSeconds = 0.8f;
        } else if (titleHasSneak) {
            mc.options.keyShift.setDown(true);
        } else if (titleHasJump) {
            if (mc.player.onGround()) {
                mc.player.jumpFromGround();
            }
            mc.options.keyJump.setDown(true);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (bruteForceTotalTicks <= 0 || !Utils.canUpdate()) return;

        stepTimeElapsed += (float) event.frameTime;

        float rawProgress = Math.min(1.0f, stepTimeElapsed / stepDurationSeconds);

        // Cosine S-curve easing for cinematic acceleration & deceleration
        float smoothProgress = 0.5f * (1.0f - (float) Math.cos(PI_FLOAT * rawProgress));

        // Raw deltas — no wrapDegrees! Each step is designed to be <= 180° so direction is always correct
        float deltaYaw = targetYaw - startYaw;
        float deltaPitch = targetPitch - startPitch;

        float currentYaw = startYaw + deltaYaw * smoothProgress;
        float currentPitch = startPitch + deltaPitch * smoothProgress;

        // 1-2 pixel organic human micro-wobble along the perpendicular axis
        if (humanWobble.get() && rawProgress > 0.05f && rawProgress < 0.95f) {
            float wobble = (float) Math.sin(stepTimeElapsed * 22.0) * 0.14f;
            boolean isHorizontalStep = (currentLookStep == LookStep.LOOK_LEFT
                || currentLookStep == LookStep.CENTER_FROM_LEFT
                || currentLookStep == LookStep.LOOK_RIGHT
                || currentLookStep == LookStep.CENTER_FROM_RIGHT);

            if (isHorizontalStep) {
                currentPitch += wobble; // Subtle vertical wobble during horizontal turn
            } else {
                currentYaw += wobble;   // Subtle horizontal wobble during vertical pitch
            }
        }

        mc.player.setYRot(currentYaw);
        mc.player.setXRot(currentPitch);
        mc.player.yRotO = currentYaw;
        mc.player.xRotO = currentPitch;

        if (stepTimeElapsed >= stepDurationSeconds) {
            advanceLookStep();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        if (titleActiveTicks > 0) {
            titleActiveTicks--;
        }

        // 1. Brute Force Mode Logic (Tick Cadences)
        if (bruteForceTotalTicks > 0) {
            bruteForceTotalTicks--;

            int cadenceTicks = actionCadenceSeconds.get() * 20;
            if (bruteForceTotalTicks % cadenceTicks == 0) {
                if (mc.player.onGround()) {
                    mc.player.jumpFromGround();
                }
                performPunch();
                sneakPulseTicks = 8;
                mc.options.keyShift.setDown(true);
            }

            if (sneakPulseTicks > 0) {
                sneakPulseTicks--;
                if (sneakPulseTicks == 0) {
                    mc.options.keyShift.setDown(false);
                }
            }

            if (bruteForceTotalTicks == 0) {
                restoreStateIfSaved();
                info("Brute force sequence finished. Restored exact position and pitch/yaw.");
            }
            return;
        }

        // 2. Standard Title Actions
        if (titleActiveTicks > 0 && activeTitleText != null) {
            if (titleHasSneak) {
                mc.options.keyShift.setDown(true);
            }
            if (titleHasJump) {
                if (mc.player.onGround() && random.nextInt(5) == 0) {
                    mc.player.jumpFromGround();
                }
            }
            if (titleHasLookLeft || titleHasLookRight) {
                stepTimeElapsed += 0.05f;
                float rawProgress = Math.min(1.0f, stepTimeElapsed / stepDurationSeconds);
                float smoothProgress = 0.5f * (1.0f - (float) Math.cos(PI_FLOAT * rawProgress));
                float deltaYaw = targetYaw - startYaw;
                float currentYaw = startYaw + deltaYaw * smoothProgress;
                mc.player.setYRot(currentYaw);
                mc.player.yRotO = currentYaw;
            }
            return;
        }

        // Title expired -> restore state
        if (savedState && titleActiveTicks == 0) {
            restoreStateIfSaved();
            activeTitleText = null;
            info("Title display ended. Restored position & rotation.");
        }
    }
}
