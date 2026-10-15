/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.accounts;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.accounts.MicrosoftLogin;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import java.util.concurrent.atomic.AtomicReference;

public class AddMicrosoftAccountScreen extends AddAccountScreen {
    private WLabel status;
    private MicrosoftLogin.LoginAttempt attempt;
    private String verificationUri;

    public AddMicrosoftAccountScreen(GuiTheme theme, AccountsScreen parent) {
        super(theme, "Add Microsoft Account", parent);
    }

    @Override
    public void initWidgets() {
        add(theme.label("Follow the Microsoft device sign-in instructions in your browser."));
        add(theme.label("If the browser does not open, copy the verification link below."));
        status = add(theme.label("Preparing Microsoft sign-in...")).expandX().widget();

        WHorizontalList l = add(theme.horizontalList()).expandX().widget();

        WButton copy = l.add(theme.button("Copy link")).expandX().widget();
        copy.action = () -> {
            if (verificationUri != null) mc.keyboardHandler.setClipboard(verificationUri);
        };

        WButton cancel = l.add(theme.button("Cancel")).expandX().widget();
        cancel.action = () -> {
            if (locked) return;
            cancelAttempt();
            onClose();
        };

        if (attempt == null) startAttempt();
    }

    private void startAttempt() {
        AtomicReference<MicrosoftLogin.LoginAttempt> handle = new AtomicReference<>();
        MicrosoftLogin.LoginAttempt created = MicrosoftLogin.newLoginAttempt(
            uri -> mc.execute(() -> {
                if (!isCurrentAttempt(handle.get())) return;
                verificationUri = uri;
                status.set("Waiting for Microsoft sign-in. If needed, copy the link and finish in your browser.");
            }),
            result -> mc.execute(() -> {
                if (!isCurrentAttempt(handle.get())) return;
                if (!result.isGood()) {
                    status.set(result.error);
                    return;
                }

                status.set("Microsoft sign-in complete. Activating your Minecraft account...");
                AccountsScreen.addMicrosoftAccount(this, parent, result.data, error -> status.set(error));
            })
        );
        handle.set(created);
        attempt = created;
        created.start();
    }

    private boolean isCurrentAttempt(MicrosoftLogin.LoginAttempt candidate) {
        return candidate != null && attempt == candidate && !candidate.isCancelled();
    }

    private void cancelAttempt() {
        if (attempt != null) attempt.cancel();
    }

    @Override
    public void removed() {
        cancelAttempt();
        super.removed();
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
