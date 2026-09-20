package com.github.dhangofa.batteryremapper;

import android.app.AlertDialog;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * BatteryRemapper, built on the modern Xposed API (libxposed API 102).
 *
 * <p>Everything runs inside {@code com.android.systemui}: the module rewrites the value of
 * {@link Intent#getIntExtra(String, int)} whenever System UI asks for
 * {@link BatteryManager#EXTRA_LEVEL}, so the displayed battery scale is stretched from a
 * configurable physical window onto 0%..100%.
 *
 * <p>Configuration comes from the app through {@link SettingsProvider}, and the app reports a
 * change with {@link SettingsProvider#ACTION_SETTINGS_CHANGED}. The module also answers the
 * app's {@link SettingsProvider#ACTION_PROBE_HOOK} probe so the status card can tell whether the
 * hook is really running.
 *
 * <p>Three behaviours, all preserved from the original module:
 * <ol>
 *   <li>the visual remap of the reported battery level,</li>
 *   <li>battery saver hysteresis driven by the <em>displayed</em> level (forced ON at or below
 *       20%, forced OFF above 50%, always OFF while charging),</li>
 *   <li>a 30 second shutdown countdown driven by the <em>displayed</em> level, at or below a
 *       configurable trigger, cancelled as soon as the charger is connected.</li>
 * </ol>
 */
public class BatteryHook extends XposedModule {

    private static final String TAG = "BatteryRemapper";

    /** Displayed level at or below which battery saver is forced ON (while unplugged). */
    private static final int SAVER_ON_LEVEL = 20;

    /** Displayed level above which battery saver is forced OFF (while unplugged). */
    private static final int SAVER_OFF_LEVEL = 50;

    private static final long SHUTDOWN_COUNTDOWN_MS = 30_000L;
    private static final long COUNTDOWN_TICK_MS = 1_000L;

    /** Used only if the app's own strings cannot be read. */
    private static final String FALLBACK_DIALOG_TITLE =
            "Battery depleted / 电量耗尽";

    private static final String FALLBACK_DIALOG_MESSAGE =
            "Device will shut down in %1$d seconds.\nPlug in the charger to cancel."
                    + "\n\n设备将在 %1$d 秒后关机。\n插入充电器即可取消。";

    // ------------------------------------------------------------------
    // Process-wide state
    //
    // A hot reload builds a *new* module instance in the same process, loaded through a new
    // classloader, and statics are per-classloader. Anything that has to survive such a reload
    // (or simply be shared by every instance of this process) lives here, and the ownership
    // token below makes sure only one instance acts on it.
    // ------------------------------------------------------------------

    /** Newest instance, so the static helpers can log through the framework. */
    private static volatile BatteryHook sInstance;

    private static volatile Context sAppContext;
    private static volatile boolean sSettingsLoaded = false;

    private static volatile boolean sRemapperEnabled =
            AppPreferences.DEFAULT_REMAPPER_ENABLED;
    private static volatile boolean sBatterySaverEnabled =
            AppPreferences.DEFAULT_BATTERY_SAVER_ENABLED;
    private static volatile boolean sAutoShutdownEnabled =
            AppPreferences.DEFAULT_AUTO_SHUTDOWN_ENABLED;

    private static volatile int sMapMin = AppPreferences.DEFAULT_MAP_MIN;
    private static volatile int sMapMax = AppPreferences.DEFAULT_MAP_MAX;
    private static volatile int sShutdownTrigger =
            AppPreferences.DEFAULT_SHUTDOWN_TRIGGER;

    /** -1 = Neutral/Unknown, 0 = Force OFF, 1 = Force ON. */
    private static volatile int sAppliedSaverState = -1;

    private static volatile boolean sShuttingDown = false;
    private static volatile AlertDialog sShutdownDialog = null;
    private static volatile CountDownTimer sShutdownTimer = null;

    private static volatile boolean sBatteryReceiverRegistered = false;
    private static volatile boolean sStatusReceiverRegistered = false;
    private static volatile boolean sSettingsReceiverRegistered = false;
    private static volatile boolean sWarnedMissingContext = false;
    private static volatile boolean sWarnedFallbackFailure = false;

    /** Guards the one-time catch-up that runs as soon as a {@link Context} exists. */
    private static volatile boolean sDeferredSetupRequested = false;

    /** True once this instance has installed its hooks, so a reload never double-hooks. */
    private boolean hooksInstalled;

    /**
     * JVM-wide ownership token.
     *
     * <p>A hot reload does not merely build a new module instance: it loads the module classes
     * through a <em>new classloader</em>, and {@code static} fields are per-classloader. Every
     * reload therefore leaves a fully live ghost behind — with its own configuration, its own
     * receivers and its own dialog reference — which is how a countdown could be armed by a
     * trigger the user had already lowered. System properties are the one piece of JVM state
     * every classloader shares, so ownership travels through one: the newest instance
     * overwrites the token and anything still holding an older token stops acting.
     */
    private static final String OWNER_PROPERTY = "batteryremapper.owner";

    private static final String GENERATION =
            Long.toHexString(System.nanoTime());

    private static boolean isOwner() {
        return GENERATION.equals(System.getProperty(OWNER_PROPERTY));
    }

    private static void claimOwnership() {
        System.setProperty(OWNER_PROPERTY, GENERATION);
    }

    // ------------------------------------------------------------------
    // Receivers
    // ------------------------------------------------------------------

    /**
     * Answers the app's status probe.
     *
     * <p>This is the app's existing protocol: it broadcasts a probe with a request id, and the
     * hook replies with that id plus the package it is running in, so a card that never gets an
     * answer can say the module is not loaded.
     */
    private static final BroadcastReceiver STATUS_PROBE_RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null
                            || !SettingsProvider.ACTION_PROBE_HOOK.equals(
                                    intent.getAction())) {
                        return;
                    }

                    // A leftover instance from an earlier reload must not answer.
                    if (!isOwner()) {
                        return;
                    }

                    Intent response =
                            new Intent(SettingsProvider.ACTION_HOOK_STATUS);

                    // Restrict delivery to the app that asked.
                    response.setPackage(SettingsProvider.MODULE_PACKAGE);

                    response.putExtra(
                            SettingsProvider.EXTRA_REQUEST_ID,
                            intent.getStringExtra(
                                    SettingsProvider.EXTRA_REQUEST_ID
                            )
                    );

                    response.putExtra(
                            SettingsProvider.EXTRA_HOOK_ACTIVE,
                            true
                    );

                    response.putExtra(
                            SettingsProvider.EXTRA_HOOKED_PACKAGE,
                            SettingsProvider.SYSTEM_UI_PACKAGE
                    );

                    try {
                        context.sendBroadcast(response);
                    } catch (Throwable t) {
                        logThrowable(Log.WARN, "could not answer the status probe", t);
                    }
                }
            };

    /** Re-reads the settings whenever the app reports a change, then applies them live. */
    private static final BroadcastReceiver SETTINGS_CHANGED_RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null
                            || !SettingsProvider.ACTION_SETTINGS_CHANGED.equals(
                                    intent.getAction())) {
                        return;
                    }

                    if (!isOwner()) {
                        return;
                    }

                    logMessage(Log.INFO, "settings changed, reloading");
                    loadSettings();
                }
            };

    /**
     * Watches the battery directly rather than waiting for System UI to read a level.
     *
     * <p>Cancelling the countdown must not depend on some other component deciding to call
     * {@code getIntExtra(EXTRA_LEVEL)}: reading the sticky broadcast costs nothing and makes
     * plugging in the charger cancel the countdown immediately, whatever System UI is doing.
     */
    private static final BroadcastReceiver BATTERY_RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // A reloaded instance leaves this receiver registered in its own
                    // classloader; only the newest instance may act, or several copies would
                    // each arm a countdown.
                    if (!isOwner()) {
                        if (sShuttingDown
                                || sShutdownDialog != null
                                || sShutdownTimer != null) {
                            sShuttingDown = false;
                            logMessage(Log.INFO,
                                    "a superseded module instance is standing down");
                            dismissCountdownUi();
                        }
                        return;
                    }

                    int[] state = physicalBatteryState(intent);

                    if (state == null) {
                        return;
                    }

                    // The countdown works on the displayed scale, like everything the user sees.
                    handleShutdownLogic(
                            Mapping.remap(state[0], sMapMin, sMapMax),
                            state[1]
                    );
                }
            };

    // ------------------------------------------------------------------
    // Module lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sInstance = this;
        claimOwnership();

        log(Log.INFO, TAG, "loaded into " + param.getProcessName()
                + " (systemServer=" + param.isSystemServer() + ") on "
                + getFrameworkName() + " " + getFrameworkVersion()
                + ", API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // The module only ever touches System UI.
        if (!SettingsProvider.SYSTEM_UI_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        sInstance = this;
        claimOwnership();
        ensureContext();

        loadSettings();
        installHooks();
        registerBatteryReceiver();

        logMessage(Log.INFO, "System UI hooked, physical " + sMapMin + "%.." + sMapMax
                + "% -> displayed 0%..100%, countdown at displayed " + sShutdownTrigger
                + "%, remapping " + (sRemapperEnabled ? "on" : "off"));
    }

    // ------------------------------------------------------------------
    // Logging (the static helpers need an instance to reach the framework)
    // ------------------------------------------------------------------

    private static void logMessage(int priority, String message) {
        BatteryHook instance = sInstance;

        if (instance == null) {
            Log.println(priority, TAG, message);
            return;
        }

        try {
            instance.log(priority, TAG, message);
        } catch (Throwable t) {
            Log.println(priority, TAG, message);
        }
    }

    private static void logThrowable(int priority, String message, Throwable t) {
        BatteryHook instance = sInstance;

        if (instance == null) {
            Log.println(priority, TAG, message + ": " + t);
            return;
        }

        try {
            instance.log(priority, TAG, message, t);
        } catch (Throwable ignored) {
            Log.println(priority, TAG, message + ": " + t);
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Reads the current configuration from the app's settings provider. */
    private static void loadSettings() {
        Context context = ensureContext();

        if (context == null) {
            logMessage(Log.WARN, "no context yet, so the settings could not be read");
            return;
        }

        try {
            Bundle result = context.getContentResolver().call(
                    SettingsProvider.CONTENT_URI,
                    SettingsProvider.METHOD_GET_SETTINGS,
                    null,
                    null
            );

            if (result == null) {
                logMessage(Log.WARN, "the settings provider returned nothing");
                return;
            }

            applySettings(
                    result.getBoolean(
                            SettingsProvider.RESULT_REMAPPER_ENABLED,
                            AppPreferences.DEFAULT_REMAPPER_ENABLED
                    ),
                    result.getBoolean(
                            SettingsProvider.RESULT_BATTERY_SAVER_ENABLED,
                            AppPreferences.DEFAULT_BATTERY_SAVER_ENABLED
                    ),
                    result.getBoolean(
                            SettingsProvider.RESULT_AUTO_SHUTDOWN_ENABLED,
                            AppPreferences.DEFAULT_AUTO_SHUTDOWN_ENABLED
                    ),
                    result.getInt(
                            SettingsProvider.RESULT_MAP_MIN,
                            AppPreferences.DEFAULT_MAP_MIN
                    ),
                    result.getInt(
                            SettingsProvider.RESULT_MAP_MAX,
                            AppPreferences.DEFAULT_MAP_MAX
                    ),
                    result.getInt(
                            SettingsProvider.RESULT_SHUTDOWN_TRIGGER,
                            AppPreferences.DEFAULT_SHUTDOWN_TRIGGER
                    )
            );
        } catch (Throwable t) {
            logThrowable(Log.WARN, "could not read the settings provider", t);
        }
    }

    private static void applySettings(
            boolean remapperEnabled,
            boolean batterySaverEnabled,
            boolean autoShutdownEnabled,
            int mapMin,
            int mapMax,
            int shutdownTrigger
    ) {
        int[] range = AppPreferences.normalizeRange(mapMin, mapMax);
        int trigger = AppPreferences.normalizeShutdownTrigger(shutdownTrigger);

        boolean changed =
                !sSettingsLoaded
                        || remapperEnabled != sRemapperEnabled
                        || batterySaverEnabled != sBatterySaverEnabled
                        || autoShutdownEnabled != sAutoShutdownEnabled
                        || range[0] != sMapMin
                        || range[1] != sMapMax
                        || trigger != sShutdownTrigger;

        if (!changed) {
            return;
        }

        // Only a change to how the battery is displayed needs the battery re-read.
        boolean displayChanged =
                !sSettingsLoaded
                        || remapperEnabled != sRemapperEnabled
                        || range[0] != sMapMin
                        || range[1] != sMapMax;

        // A changed trigger has to be judged against the battery as it is right now.
        boolean triggerChanged =
                !sSettingsLoaded || trigger != sShutdownTrigger;

        sRemapperEnabled = remapperEnabled;
        sBatterySaverEnabled = batterySaverEnabled;
        sAutoShutdownEnabled = autoShutdownEnabled;
        sMapMin = range[0];
        sMapMax = range[1];
        sShutdownTrigger = trigger;
        sSettingsLoaded = true;

        logMessage(Log.INFO, "settings applied: remapping=" + sRemapperEnabled
                + ", physical " + sMapMin + "%.." + sMapMax + "% -> displayed 0%..100%"
                + ", saver=" + sBatterySaverEnabled
                + ", countdown=" + sAutoShutdownEnabled
                + " at displayed " + sShutdownTrigger + "%");

        // Every path that changes what the user sees ends here, so this is the one place that
        // has to guarantee a context before the follow-up work below.
        ensureContext();

        if (displayChanged) {
            requestBatteryRefresh();
        }

        if (triggerChanged || !sAutoShutdownEnabled) {
            evaluateCountdownNow();
        }
    }

    /**
     * Asks System UI to re-read the battery level.
     *
     * <p>System UI only recomputes the displayed percentage when an
     * {@code ACTION_BATTERY_CHANGED} broadcast arrives, so a setting that changes while the
     * battery itself is steady would otherwise stay invisible until the next real battery event
     * (which is why restarting System UI used to look like the only way to apply a change).
     * Re-sending the current sticky intent, scoped to System UI itself, makes the status bar
     * pick the new window up immediately.
     */
    private static void requestBatteryRefresh() {
        Context context = ensureContext();

        if (context == null) {
            logMessage(Log.WARN, "no System UI context yet, so the battery re-read was skipped");
            return;
        }

        try {
            Intent current = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (current == null) {
                logMessage(Log.WARN, "no sticky battery intent to re-send");
                return;
            }

            Intent refresh = new Intent(current);

            // Never leak a battery broadcast to other apps.
            refresh.setPackage(context.getPackageName());
            context.sendBroadcast(refresh);

            logMessage(Log.INFO, "asked System UI to re-read the battery level");
        } catch (Throwable t) {
            // Some ROMs protect this broadcast; the next real battery event still applies.
            logThrowable(Log.WARN, "could not request a battery refresh", t);
        }
    }

    /** Re-judges the countdown against the battery state right now. */
    private static void evaluateCountdownNow() {
        Context context = ensureContext();

        if (context == null) {
            return;
        }

        try {
            Intent battery = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            int[] state = physicalBatteryState(battery);

            if (state != null) {
                handleShutdownLogic(
                        Mapping.remap(state[0], sMapMin, sMapMax),
                        state[1]
                );
            }
        } catch (Throwable t) {
            logThrowable(Log.WARN, "could not re-evaluate the countdown state", t);
        }
    }

    /**
     * Reads the true physical battery state out of a battery intent.
     *
     * <p>{@link Bundle#getInt} is used on purpose: calling {@code getIntExtra} here would run
     * through the module's own hook and hand back the remapped level instead.
     */
    private static int[] physicalBatteryState(Intent battery) {
        Bundle extras = battery == null ? null : battery.getExtras();

        if (extras == null) {
            return null;
        }

        int level = extras.getInt(BatteryManager.EXTRA_LEVEL, -1);

        if (level < 0) {
            return null;
        }

        return new int[]{
                level,
                extras.getInt(BatteryManager.EXTRA_PLUGGED, 0)
        };
    }

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    /**
     * Installs every hook of this instance exactly once.
     *
     * @return whether this instance currently has working hooks installed
     */
    private boolean installHooks() {
        if (hooksInstalled) {
            return true;
        }

        hooksInstalled = true;

        try {
            hookContextCapture();
            hookBatteryLevel();
            registerProtocolReceivers();
            return true;
        } catch (Throwable t) {
            hooksInstalled = false;
            logThrowable(Log.ERROR, "failed to install hooks", t);
            return false;
        }
    }

    /** Registers the two receivers that make up the app/hook protocol. */
    private void registerProtocolReceivers() {
        Context context = ensureContext();

        if (context == null) {
            logMessage(Log.WARN, "no context yet, so the app protocol is not registered");
            return;
        }

        if (!sStatusReceiverRegistered) {
            registerReceiver(
                    context,
                    STATUS_PROBE_RECEIVER,
                    SettingsProvider.ACTION_PROBE_HOOK,
                    true
            );
            sStatusReceiverRegistered = true;
        }

        if (!sSettingsReceiverRegistered) {
            registerReceiver(
                    context,
                    SETTINGS_CHANGED_RECEIVER,
                    SettingsProvider.ACTION_SETTINGS_CHANGED,
                    true
            );
            sSettingsReceiverRegistered = true;
        }
    }

    /** Registers the module's own battery listener once per process. */
    private void registerBatteryReceiver() {
        if (sBatteryReceiverRegistered) {
            return;
        }

        Context context = ensureContext();

        if (context == null) {
            logMessage(Log.WARN, "no context yet, so battery changes are not watched directly");
            return;
        }

        registerReceiver(
                context,
                BATTERY_RECEIVER,
                Intent.ACTION_BATTERY_CHANGED,
                false
        );

        sBatteryReceiverRegistered = true;

        logMessage(Log.INFO, "watching battery changes directly, so connecting the charger "
                + "cancels the countdown on its own");
    }

    /**
     * Registers a receiver, taking care of the exported flag Android 12+ requires.
     *
     * <p>A broadcast from the app needs an exported receiver; a system broadcast such as
     * {@code ACTION_BATTERY_CHANGED} is delivered either way and is not exported.
     */
    private void registerReceiver(
            Context context,
            BroadcastReceiver receiver,
            String action,
            boolean exported
    ) {
        IntentFilter filter = new IntentFilter(action);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                    receiver,
                    filter,
                    exported
                            ? Context.RECEIVER_EXPORTED
                            : Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /**
     * Hooks a method, forcing ART to deoptimise it first.
     *
     * <p>This is not optional for small framework methods. ART inlines a method such as
     * {@link ContextWrapper#attachBaseContext}, whose body is a single field assignment, into
     * every caller; the hook is then installed successfully but never invoked, which fails
     * silently.
     */
    private void hookDeoptimized(Method method, XposedInterface.Hooker hooker) {
        try {
            log(Log.INFO, TAG, "deoptimize " + method.getDeclaringClass().getSimpleName()
                    + "#" + method.getName() + " -> " + deoptimize(method));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "could not deoptimize " + method.getName(), t);
        }

        hook(method).intercept(hooker);
    }

    private static void setAppContext(Context context) {
        if (context == null || sAppContext != null) {
            return;
        }

        sAppContext = context;

        logMessage(Log.INFO, "captured the System UI context ("
                + context.getClass().getName() + ")");

        runDeferredSetup();
    }

    /**
     * Runs the part of the setup that needed a {@link Context} but found none in time.
     *
     * <p>{@code onPackageLoaded} is delivered before System UI has attached its base context, so
     * the settings read, the app-status probe and the battery listener were all skipped there.
     * Without this catch-up the module keeps running on its default range and the app's status
     * card keeps reporting that the module is not loaded, even though the hooks themselves are
     * installed and remapping already works.
     *
     * <p>Posted to the main looper because it is triggered from inside the
     * {@code attachBaseContext} / {@code onCreate} interception, where doing registry and
     * provider work inline is not safe.
     */
    private static void runDeferredSetup() {
        if (sDeferredSetupRequested) {
            return;
        }

        sDeferredSetupRequested = true;

        Runnable catchUp = () -> {
            try {
                loadSettings();

                BatteryHook instance = sInstance;

                if (instance != null) {
                    instance.registerProtocolReceivers();
                    instance.registerBatteryReceiver();
                }
            } catch (Throwable t) {
                logThrowable(Log.WARN, "could not finish the setup once the context arrived", t);
            }
        };

        try {
            new Handler(Looper.getMainLooper()).post(catchUp);
        } catch (Throwable t) {
            // No main looper: this only happens outside an app process, so run inline.
            catchUp.run();
        }
    }

    /**
     * Returns a usable {@link Context}, recovering one through
     * {@code ActivityThread.currentApplication()} if no hook ever handed one over.
     *
     * <p>The reflection route is what the legacy {@code AndroidAppHelper.currentApplication()}
     * did, so it works at any point in the process rather than only at startup.
     */
    private static Context ensureContext() {
        Context context = sAppContext;

        if (context != null) {
            return context;
        }

        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication =
                    activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);

            Object application = currentApplication.invoke(null);

            if (application instanceof Context) {
                logMessage(Log.INFO, "recovered the System UI context through ActivityThread");
                setAppContext((Context) application);
            }
        } catch (Throwable t) {
            if (!sWarnedFallbackFailure) {
                sWarnedFallbackFailure = true;
                logThrowable(Log.WARN, "could not recover a context through ActivityThread", t);
            }
        }

        return sAppContext;
    }

    /**
     * Keeps a usable {@link Context} around for battery saver, the shutdown dialog and the
     * battery re-read.
     *
     * <p>Two sources, because neither alone is enough: {@link Application#onCreate()} runs once
     * per process and may already be over by the time the module is loaded, while
     * {@link ContextWrapper#attachBaseContext} runs for every wrapper System UI builds.
     */
    private void hookContextCapture() throws NoSuchMethodException {
        Method onCreate = Application.class.getDeclaredMethod("onCreate");
        hookDeoptimized(onCreate, chain -> {
            Object self = chain.getThisObject();

            if (self instanceof Context) {
                setAppContext((Context) self);
            }

            return chain.proceed();
        });

        Method attachBaseContext =
                ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);

        hookDeoptimized(attachBaseContext, chain -> {
            Object result = chain.proceed();

            if (sAppContext == null) {
                try {
                    Object self = chain.getThisObject();

                    if (self instanceof Context) {
                        setAppContext(((Context) self).getApplicationContext());
                    }
                } catch (Throwable ignored) {
                    // Too early in the process; a later wrapper will provide it.
                }
            }

            return result;
        });
    }

    /**
     * Intercepts the battery level System UI reads out of the sticky
     * {@code ACTION_BATTERY_CHANGED} intent.
     */
    private void hookBatteryLevel() throws NoSuchMethodException {
        Method getIntExtra =
                Intent.class.getDeclaredMethod("getIntExtra", String.class, int.class);

        hookDeoptimized(getIntExtra, chain -> {
            Object original = chain.proceed();

            // Only the battery level is touched; every other read stays untouched.
            if (!(original instanceof Integer)
                    || !BatteryManager.EXTRA_LEVEL.equals(chain.getArg(0))) {
                return original;
            }

            // A leftover instance from an earlier reload must not remap with a window the user
            // has already replaced. Returning the chain's own result leaves the newest hook's
            // work untouched even if this one sits further out in the chain.
            if (!isOwner()) {
                return original;
            }

            int physicalLevel = (Integer) original;

            int plugged = 0;
            Object self = chain.getThisObject();

            if (self instanceof Intent) {
                Bundle extras = ((Intent) self).getExtras();

                if (extras != null) {
                    plugged = extras.getInt(BatteryManager.EXTRA_PLUGGED, 0);
                }
            }

            // The master switch is off: report the true level and touch nothing else.
            if (!sRemapperEnabled) {
                return original;
            }

            int displayedLevel = Mapping.remap(physicalLevel, sMapMin, sMapMax);

            // 1. BATTERY SAVER HYSTERESIS LOGIC (driven by the displayed level)
            Context context = ensureContext();

            if (context != null) {
                if (sBatterySaverEnabled) {
                    handleBatterySaverLogic(context, displayedLevel, plugged);
                } else if (sAppliedSaverState != -1) {
                    // The user just turned the automation off; leave saver as it is.
                    sAppliedSaverState = -1;
                }
            } else if (!sWarnedMissingContext) {
                sWarnedMissingContext = true;
                logMessage(Log.WARN, "no System UI context: battery saver automation and the "
                        + "shutdown dialog stay disabled until one is captured");
            }

            // 2. SHUTDOWN TIMER LOGIC (driven by the displayed level)
            if (sAutoShutdownEnabled) {
                handleShutdownLogic(displayedLevel, plugged);
            }

            // 3. APPLY VISUAL SPOOF
            return displayedLevel;
        });
    }

    // ------------------------------------------------------------------
    // Battery saver hysteresis
    // ------------------------------------------------------------------

    private static void handleBatterySaverLogic(Context context, int level, int plugged) {
        // CHARGING: Force OFF immediately
        if (plugged != 0) {
            if (sAppliedSaverState != 0) {
                setBatterySaver(context, false);
                sAppliedSaverState = 0;
            }
            return;
        }

        // UNPLUGGED: Hysteresis Logic
        if (level <= SAVER_ON_LEVEL) {
            // Below 20%: Force ON
            if (sAppliedSaverState != 1) {
                setBatterySaver(context, true);
                sAppliedSaverState = 1;
            }
        } else if (level > SAVER_OFF_LEVEL) {
            // Above 50%: Force OFF
            if (sAppliedSaverState != 0) {
                setBatterySaver(context, false);
                sAppliedSaverState = 0;
            }
        }
        // Levels 21-50: Do nothing (Maintain state)
    }

    private static void setBatterySaver(Context context, boolean enable) {
        try {
            // 1. Native API
            Object powerManager = context.getSystemService(Context.POWER_SERVICE);

            if (powerManager != null) {
                Method setPowerSaveModeEnabled = powerManager.getClass()
                        .getMethod("setPowerSaveModeEnabled", boolean.class);
                setPowerSaveModeEnabled.setAccessible(true);
                setPowerSaveModeEnabled.invoke(powerManager, enable);
            }

            // 2. ROM-Specific UI Authorized Fallback
            try {
                Class<?> saverUtils = Class.forName(
                        "com.android.settingslib.fuelgauge.BatterySaverUtils",
                        false,
                        context.getClassLoader()
                );

                Method setPowerSaveMode = saverUtils.getMethod(
                        "setPowerSaveMode",
                        Context.class,
                        boolean.class,
                        boolean.class
                );

                setPowerSaveMode.setAccessible(true);
                setPowerSaveMode.invoke(null, context, enable, true);
            } catch (Throwable ignored) {
            }

            logMessage(Log.INFO, "battery saver -> " + (enable ? "ON" : "OFF"));
        } catch (Throwable t) {
            logThrowable(Log.ERROR, "battery saver toggle failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Shutdown countdown
    // ------------------------------------------------------------------

    private static void handleShutdownLogic(int displayedLevel, int plugged) {
        if (!sAutoShutdownEnabled) {
            return;
        }

        if (displayedLevel <= sShutdownTrigger && plugged == 0) {
            if (!sShuttingDown) {
                sShuttingDown = true;
                logMessage(Log.INFO, "shutdown countdown armed: displayed " + displayedLevel
                        + "% <= trigger " + sShutdownTrigger + "%, unplugged");
                startCountdown();
            }
        } else if (sShuttingDown || sShutdownDialog != null || sShutdownTimer != null) {
            // Deliberately unconditional: deciding purely from the flag would strand a dialog
            // on screen for good if the flag and the UI ever got out of step.
            logMessage(Log.INFO, "shutdown countdown cancelled: displayed " + displayedLevel
                    + "%, trigger " + sShutdownTrigger + "%, plugged=" + plugged);
            cancelCountdown();
        }
    }

    // Suppressed for the window type below, which is deprecated but deliberate.
    @SuppressWarnings("deprecation")
    private static void startCountdown() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context context = ensureContext();

                if (context == null) {
                    logMessage(Log.WARN, "no context, so the shutdown countdown cannot be shown");
                    sShuttingDown = false;
                    return;
                }

                // A countdown from a previous run is still on screen: take it over.
                dismissCountdownUi();

                int startSeconds = (int) (SHUTDOWN_COUNTDOWN_MS / COUNTDOWN_TICK_MS);

                AlertDialog.Builder builder = new AlertDialog.Builder(
                        context,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert
                );

                builder.setTitle(moduleString(
                        R.string.shutdown_dialog_title,
                        FALLBACK_DIALOG_TITLE
                ));

                builder.setMessage(countdownMessage(startSeconds));
                builder.setCancelable(false);

                sShutdownDialog = builder.create();

                /*
                 * The module runs inside System UI, which is the system uid, and among the system
                 * window types this is the one the dialog actually appears with. The modern
                 * replacement (TYPE_APPLICATION_OVERLAY) needs the overlay permission and shows
                 * nothing from here, so the deprecated type is kept on purpose.
                 */
                sShutdownDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                );
                sShutdownDialog.show();

                sShutdownTimer = new CountDownTimer(
                        SHUTDOWN_COUNTDOWN_MS,
                        COUNTDOWN_TICK_MS
                ) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        AlertDialog dialog = sShutdownDialog;

                        if (dialog != null && dialog.isShowing()) {
                            dialog.setMessage(countdownMessage(
                                    (int) (millisUntilFinished / COUNTDOWN_TICK_MS)
                            ));
                        }
                    }

                    @Override
                    public void onFinish() {
                        cancelCountdown();
                        triggerShutdown();
                    }
                }.start();
            } catch (Throwable t) {
                logThrowable(Log.ERROR, "countdown UI failure", t);
                triggerShutdown();
            }
        });
    }

    private static void cancelCountdown() {
        sShuttingDown = false;
        new Handler(Looper.getMainLooper()).post(BatteryHook::dismissCountdownUi);
    }

    private static void dismissCountdownUi() {
        CountDownTimer timer = sShutdownTimer;

        if (timer != null) {
            timer.cancel();
            sShutdownTimer = null;
        }

        AlertDialog dialog = sShutdownDialog;

        if (dialog != null) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }

            sShutdownDialog = null;
        }
    }

    private static void triggerShutdown() {
        try {
            Context context = ensureContext();

            if (context != null) {
                Intent intent = new Intent(
                        "com.android.internal.intent.action.REQUEST_SHUTDOWN"
                );

                intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);

                logMessage(Log.INFO, "executed shutdown");
            }
        } catch (Throwable t) {
            logThrowable(Log.ERROR, "shutdown failed", t);
            sShuttingDown = false;
        }
    }

    private static String countdownMessage(int seconds) {
        String template = moduleString(
                R.string.shutdown_dialog_message,
                FALLBACK_DIALOG_MESSAGE
        );

        try {
            return String.format(template, seconds);
        } catch (Throwable t) {
            return String.format(FALLBACK_DIALOG_MESSAGE, seconds);
        }
    }

    /**
     * Reads one of the app's own strings.
     *
     * <p>The dialog is raised from System UI's process, where the app's resources are not the
     * current ones, so they are fetched through a context for the app's package. The strings
     * carry both languages, which keeps the warning readable whatever the system language is
     * and whatever the app's own screen is showing.
     */
    private static String moduleString(int resId, String fallback) {
        Context context = ensureContext();

        if (context != null) {
            try {
                return context
                        .createPackageContext(SettingsProvider.MODULE_PACKAGE, 0)
                        .getString(resId);
            } catch (Throwable t) {
                logThrowable(Log.WARN, "could not read the app's own strings", t);
            }
        }

        return fallback;
    }
}
