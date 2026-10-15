/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.accounts;

import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.util.Util;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class MicrosoftLogin {
    private MicrosoftLogin() {
    }

    public static class LoginData {
        public String mcToken;
        public String newRefreshToken;
        public String uuid, username;

        public LoginData() {
        }

        public LoginData(String mcToken, String newRefreshToken, String uuid, String username) {
            this.mcToken = mcToken;
            this.newRefreshToken = newRefreshToken;
            this.uuid = uuid;
            this.username = username;
        }

        public boolean isGood() {
            return !isBlank(mcToken) && !isBlank(newRefreshToken) && isValidProfileId(uuid) && isValidProfileName(username);
        }
    }

    public static final class LoginResult {
        public final LoginData data;
        public final String error;

        LoginResult(LoginData data, String error) {
            this.data = data;
            this.error = error;
        }

        public boolean isGood() {
            return data != null && data.isGood();
        }

        private static LoginResult success(LoginData data) {
            return new LoginResult(data, null);
        }

        private static LoginResult failure(String error) {
            return new LoginResult(null, error);
        }
    }

    private static final MsaApplicationConfig APPLICATION_CONFIG = new MsaApplicationConfig(
        MsaConstants.JAVA_TITLE_ID,
        MsaConstants.SCOPE_TITLE_AUTH
    );

    private static final ThreadLocal<String> LAST_LOGIN_ERROR = new ThreadLocal<>();

    /**
     * A cancellable device-code sign-in. Create it, assign it to the screen, then call start().
     */
    public static final class LoginAttempt {
        private final Consumer<String> onVerificationUri;
        private final Consumer<LoginResult> onComplete;
        private final LoginFlow flow;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean verificationPublished = new AtomicBoolean();
        private volatile Future<?> future;

        private LoginAttempt(Consumer<String> onVerificationUri, Consumer<LoginResult> onComplete, LoginFlow flow) {
            this.onVerificationUri = onVerificationUri;
            this.onComplete = onComplete;
            this.flow = flow;
        }

        public void start() {
            if (!started.compareAndSet(false, true) || cancelled.get()) return;
            future = MeteorExecutor.executor.submit(this::run);
            if (cancelled.get()) future.cancel(true);
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Future<?> task = future;
            if (task != null) task.cancel(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        private void run() {
            try {
                LoginResult result = flow.authenticate(this::publishVerificationUri, cancelled);
                complete(result == null ? LoginResult.failure("Microsoft sign-in did not finish. Return to Minecraft and retry.") : result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!cancelled.get()) complete(LoginResult.failure("Microsoft sign-in was interrupted. Return to Minecraft and retry."));
            } catch (RuntimeException e) {
                if (!cancelled.get()) {
                    String error = safeFailure("Microsoft sign-in", e);
                    complete(LoginResult.failure(error));
                }
            }
        }

        private void publishVerificationUri(String uri) {
            if (cancelled.get() || isBlank(uri) || !verificationPublished.compareAndSet(false, true)) return;

            if (!cancelled.get()) {
                try {
                    onVerificationUri.accept(uri);
                } catch (RuntimeException ignored) {
                }
            }
        }

        private void complete(LoginResult result) {
            if (cancelled.get() || !completed.compareAndSet(false, true)) return;
            try {
                onComplete.accept(result);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static LoginAttempt newLoginAttempt(Consumer<String> onVerificationUri, Consumer<LoginResult> onComplete) {
        if (onVerificationUri == null || onComplete == null) throw new IllegalArgumentException("Callbacks cannot be null");
        return new LoginAttempt(onVerificationUri, onComplete, MicrosoftLogin::loginWithDeviceCode);
    }

    static LoginAttempt newLoginAttempt(Consumer<String> onVerificationUri, Consumer<LoginResult> onComplete, LoginFlow flow) {
        if (onVerificationUri == null || onComplete == null || flow == null) throw new IllegalArgumentException("Callbacks and login flow cannot be null");
        return new LoginAttempt(onVerificationUri, onComplete, flow);
    }

    @FunctionalInterface
    interface LoginFlow {
        LoginResult authenticate(Consumer<String> onVerificationUri, AtomicBoolean cancelled) throws InterruptedException;
    }

    private static LoginResult loginWithDeviceCode(Consumer<String> onVerificationUri, AtomicBoolean cancelled) throws InterruptedException {
        String stage = "Microsoft device authorization";
        try {
            JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .msaApplicationConfig(APPLICATION_CONFIG)
                .login(DeviceCodeMsaAuthService::new, (Consumer<MsaDeviceCode>) deviceCode -> {
                    if (cancelled.get()) return;
                    String uri = deviceCode.getDirectVerificationUri();
                    try {
                        Util.getPlatform().openUri(uri);
                    } catch (RuntimeException e) {
                        // The Copy link button remains available if the system browser cannot open.
                    }
                    onVerificationUri.accept(uri);
                });
            if (cancelled.get()) return null;

            stage = "Microsoft token refresh";
            MsaToken msaToken = authManager.getMsaToken().getUpToDate();
            if (msaToken == null || isBlank(msaToken.getRefreshToken())) throw new LoginFailure(stage + " returned an incomplete response.");

            stage = "Minecraft sign-in";
            MinecraftToken minecraftToken = authManager.getMinecraftToken().getUpToDate();
            if (minecraftToken == null || isBlank(minecraftToken.getToken())) throw new LoginFailure(stage + " returned an incomplete response.");

            stage = "Minecraft profile lookup";
            MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
            if (profile == null || profile.getId() == null || !isValidProfileName(profile.getName())) {
                throw new LoginFailure(stage + " returned an invalid profile.");
            }

            LoginData data = new LoginData(minecraftToken.getToken(), msaToken.getRefreshToken(), profile.getId().toString(), profile.getName());
            if (!data.isGood()) throw new LoginFailure(stage + " returned an invalid profile.");
            return LoginResult.success(data);
        } catch (TimeoutException e) {
            return LoginResult.failure("Microsoft sign-in timed out or was declined. Return to Minecraft and retry.");
        } catch (IOException | RuntimeException e) {
            return LoginResult.failure(safeFailure(stage, e));
        }
    }

    public static LoginData login(String refreshToken) {
        LAST_LOGIN_ERROR.remove();
        String stage = "Microsoft saved-session refresh";
        try {
            JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .msaApplicationConfig(APPLICATION_CONFIG)
                .login(refreshToken);

            MsaToken msaToken = authManager.getMsaToken().getUpToDate();
            if (msaToken == null || isBlank(msaToken.getRefreshToken())) throw new LoginFailure(stage + " returned an incomplete response.");

            stage = "Minecraft sign-in";
            MinecraftToken minecraftToken = authManager.getMinecraftToken().getUpToDate();
            if (minecraftToken == null || isBlank(minecraftToken.getToken())) throw new LoginFailure(stage + " returned an incomplete response.");

            stage = "Minecraft profile lookup";
            MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
            if (profile == null || profile.getId() == null || !isValidProfileName(profile.getName())) throw new LoginFailure(stage + " returned an invalid profile.");

            LoginData data = new LoginData(minecraftToken.getToken(), msaToken.getRefreshToken(), profile.getId().toString(), profile.getName());
            if (!data.isGood()) throw new LoginFailure(stage + " returned an invalid profile.");
            return data;
        } catch (IOException | RuntimeException e) {
            LAST_LOGIN_ERROR.set(safeFailure(stage, e));
            return new LoginData();
        }
    }

    public static String getLastLoginError() {
        return LAST_LOGIN_ERROR.get();
    }

    private static String safeFailure(String stage, Throwable error) {
        if (error instanceof LoginFailure) return error.getMessage();

        int status = getHttpStatus(error);
        if ("Minecraft profile lookup".equals(stage) && status == 404) {
            return "Minecraft profile lookup failed (HTTP 404); this Microsoft account has no Java profile.";
        }
        if (status >= 100 && status <= 599) return stage + " failed (HTTP " + status + ").";
        if ("Microsoft saved-session refresh".equals(stage)) {
            return "Microsoft saved-session refresh failed. This saved sign-in may be from the previous login method; remove and re-add the account using Microsoft.";
        }
        return stage + " failed. Return to Minecraft and retry sign-in.";
    }

    private static int getHttpStatus(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpRequestException requestException && requestException.getResponse() != null) {
                return requestException.getResponse().getStatusCode();
            }
        }
        return -1;
    }

    private static boolean isValidProfileId(String id) {
        if (id == null) return false;
        try {
            return java.util.UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidProfileName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static class LoginFailure extends IOException {
        private LoginFailure(String message) {
            super(message);
        }
    }
}
