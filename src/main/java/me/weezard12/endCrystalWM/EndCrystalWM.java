package me.weezard12.endCrystalWM;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class EndCrystalWM {
    public static final String WATERMARK_PREFIX = "This plugin was generated for FREE using ";
    public static final String WATERMARK_BRAND = "End Crystal AI";
    public static final String WATERMARK_URL = "https://endcrystal.ai";
    public static final String WATERMARK_MESSAGE = WATERMARK_PREFIX + WATERMARK_BRAND;
    public static final long WATERMARK_PERIOD_TICKS = 20L * 30L;

    private static final String CANONICAL_PREFIX = "This plugin was generated for FREE using ";
    private static final String CANONICAL_BRAND = "End Crystal AI";
    private static final String CANONICAL_URL = "https://endcrystal.ai";
    private static final String CANONICAL_MESSAGE = CANONICAL_PREFIX + CANONICAL_BRAND;
    private static final long CANONICAL_PERIOD_TICKS = (20L * 3L) * 10L;
    private static final String EXPECTED_CONSTANT_FINGERPRINT =
            "fba660f103efc60bed0068ca244a8f3f289781190675b4f96c73ae561cc1da12";
    private static final long WATCHDOG_CHECK_INTERVAL_MILLIS = 5_000L;
    private static final String METADATA_FILENAME = "endcrystalwm-meta.json";
    private static final String[] QUERY_COMMAND_LABELS = new String[]{"endcrystalwm", "ecwm"};

    private static final Class<?>[] NO_PARAMS = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] CLASSLOADER_PLUGIN_FIELDS = new String[]{"plugin", "pluginInit", "pluginDescriptionFile"};
    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<String, Class<?>>();
    private static final Set<String> MISSING_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE =
            new ConcurrentHashMap<String, Method>();
    private static final Set<String> MISSING_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<Object> INSTALLED_PLUGINS =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static final Set<Object> COMMAND_HOOK_REFERENCES =
            Collections.newSetFromMap(new ConcurrentHashMap<Object, Boolean>());
    private static final Set<String> REPORTED_TAMPER_REASONS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static volatile boolean installed;
    private static volatile boolean bootstrapThreadStarted;
    private static volatile boolean commandHooksRegistered;
    private static volatile WeakReference<Object> installedPluginReference = new WeakReference<Object>(null);
    private static volatile Object watermarkTaskHandle;
    private static volatile Integer watermarkTaskId;
    private static volatile long lastBroadcastEpochMillis;
    private static volatile long lastWatchdogEpochMillis;
    private static volatile long lastMetadataWriteEpochMillis;
    private static volatile int tamperEventCount;
    private static volatile long lastTamperEpochMillis;
    private static volatile String lastTamperReason = "";
    private static volatile String lastObservedConstantFingerprint = "";

    static {
        verifyConstantFingerprint("static-init");
        installInternal();
        startBootstrapThread();
    }

    private EndCrystalWM() {
    }

    public static void install() {
        installInternal();
    }

    private static boolean installInternal() {
        if (installed) {
            return true;
        }

        Object plugin = findOwningPlugin();
        return plugin != null && installFor(plugin);
    }

    public static boolean installFor(Object plugin) {
        if (plugin == null) {
            return false;
        }

        Class<?> pluginClass = loadClass("org.bukkit.plugin.Plugin");
        if (pluginClass == null) {
            return false;
        }

        if (!pluginClass.isInstance(plugin)) {
            throw new IllegalArgumentException("plugin must implement org.bukkit.plugin.Plugin");
        }

        synchronized (INSTALLED_PLUGINS) {
            if (installed) {
                return true;
            }

            if (INSTALLED_PLUGINS.contains(plugin)) {
                installedPluginReference = new WeakReference<Object>(plugin);
                installed = true;
                return true;
            }

            if (!isPluginEnabled(plugin)) {
                return false;
            }

            verifyConstantFingerprint("install");
            if (!scheduleRepeatingBroadcast(pluginClass, plugin)) {
                return false;
            }

            registerAdminCommandHooks(pluginClass, plugin);
            INSTALLED_PLUGINS.add(plugin);
            installedPluginReference = new WeakReference<Object>(plugin);
            installed = true;
            broadcastNowInternal(plugin, "install");
            publishMetadataEndpoint(plugin, "install");
            return true;
        }
    }

    public static void broadcastNow() {
        Object plugin = resolveTrackedPlugin();
        broadcastNowInternal(plugin, "manual");
    }

    private static void broadcastNowInternal(Object plugin, String source) {
        verifyConstantFingerprint("broadcast-" + source);

        boolean broadcasted = false;
        if (broadcastRichWatermark()) {
            broadcasted = true;
        }

        if (!broadcasted) {
            String legacyWatermark = CANONICAL_PREFIX + "\u00A75" + CANONICAL_BRAND + "\u00A7r";
            if (!tryInvokeStatic(
                    "org.bukkit.Bukkit",
                    "broadcastMessage",
                    new Class<?>[]{String.class},
                    new Object[]{legacyWatermark}
            )) {
                Object server = invokeStatic("org.bukkit.Bukkit", "getServer", NO_PARAMS, NO_ARGS);
                if (server != null) {
                    tryInvoke(server, "broadcastMessage", new Class<?>[]{String.class}, new Object[]{legacyWatermark});
                }
            }
        }

        emitConsoleWatermark(plugin, source);
        lastBroadcastEpochMillis = System.currentTimeMillis();
        publishMetadataEndpoint(plugin, source);
    }

    private static boolean broadcastRichWatermark() {
        Object components = createWatermarkComponents();
        if (components == null) {
            return false;
        }

        Class<?> componentArrayClass = components.getClass();

        Object bukkitSpigot = invokeStatic("org.bukkit.Bukkit", "spigot", NO_PARAMS, NO_ARGS);
        if (invokeForSideEffects(
                bukkitSpigot,
                "broadcast",
                new Class<?>[]{componentArrayClass},
                new Object[]{components}
        )) {
            return true;
        }

        Object server = invokeStatic("org.bukkit.Bukkit", "getServer", NO_PARAMS, NO_ARGS);
        if (server != null) {
            Object serverSpigot = invoke(server, "spigot", NO_PARAMS, NO_ARGS);
            if (invokeForSideEffects(
                    serverSpigot,
                    "broadcast",
                    new Class<?>[]{componentArrayClass},
                    new Object[]{components}
            )) {
                return true;
            }
        }

        return broadcastToPlayers(components, componentArrayClass);
    }

    private static Object createWatermarkComponents() {
        Class<?> baseComponentClass = loadClass("net.md_5.bungee.api.chat.BaseComponent");
        Class<?> textComponentClass = loadClass("net.md_5.bungee.api.chat.TextComponent");
        Class<?> chatColorClass = loadClass("net.md_5.bungee.api.ChatColor");

        if (baseComponentClass == null || textComponentClass == null || chatColorClass == null) {
            return null;
        }

        Object prefixComponent = newInstance(
                textComponentClass,
                new Class<?>[]{String.class},
                new Object[]{CANONICAL_PREFIX}
        );
        Object brandComponent = newInstance(
                textComponentClass,
                new Class<?>[]{String.class},
                new Object[]{CANONICAL_BRAND}
        );

        if (prefixComponent == null || brandComponent == null) {
            return null;
        }

        Object purple = enumConstant(chatColorClass, "LIGHT_PURPLE");
        if (purple == null) {
            purple = enumConstant(chatColorClass, "DARK_PURPLE");
        }
        if (purple != null) {
            invokeForSideEffects(
                    brandComponent,
                    "setColor",
                    new Class<?>[]{chatColorClass},
                    new Object[]{purple}
            );
        }

        invokeForSideEffects(
                brandComponent,
                "setUnderlined",
                new Class<?>[]{boolean.class},
                new Object[]{Boolean.TRUE}
        );

        Object clickEvent = createClickEvent();
        if (clickEvent != null) {
            invokeForSideEffects(
                    brandComponent,
                    "setClickEvent",
                    new Class<?>[]{clickEvent.getClass()},
                    new Object[]{clickEvent}
            );
        }

        Object hoverEvent = createHoverEvent(baseComponentClass, textComponentClass);
        if (hoverEvent != null) {
            invokeForSideEffects(
                    brandComponent,
                    "setHoverEvent",
                    new Class<?>[]{hoverEvent.getClass()},
                    new Object[]{hoverEvent}
            );
        }

        Object components = Array.newInstance(baseComponentClass, 2);
        Array.set(components, 0, prefixComponent);
        Array.set(components, 1, brandComponent);
        return components;
    }

    private static Object createClickEvent() {
        Class<?> clickEventClass = loadClass("net.md_5.bungee.api.chat.ClickEvent");
        Class<?> clickActionClass = loadClass("net.md_5.bungee.api.chat.ClickEvent$Action");

        if (clickEventClass == null || clickActionClass == null) {
            return null;
        }

        Object openUrl = enumConstant(clickActionClass, "OPEN_URL");
        if (openUrl == null) {
            return null;
        }

        return newInstance(
                clickEventClass,
                new Class<?>[]{clickActionClass, String.class},
                new Object[]{openUrl, CANONICAL_URL}
        );
    }

    private static Object createHoverEvent(Class<?> baseComponentClass, Class<?> textComponentClass) {
        Class<?> hoverEventClass = loadClass("net.md_5.bungee.api.chat.HoverEvent");
        Class<?> hoverActionClass = loadClass("net.md_5.bungee.api.chat.HoverEvent$Action");
        if (hoverEventClass == null || hoverActionClass == null) {
            return null;
        }

        Object showText = enumConstant(hoverActionClass, "SHOW_TEXT");
        if (showText == null) {
            return null;
        }

        Object hoverLabel = newInstance(
                textComponentClass,
                new Class<?>[]{String.class},
                new Object[]{"Open " + CANONICAL_URL}
        );
        if (hoverLabel == null) {
            return null;
        }

        Object legacyTextArray = Array.newInstance(baseComponentClass, 1);
        Array.set(legacyTextArray, 0, hoverLabel);

        Object legacyHoverEvent = newInstance(
                hoverEventClass,
                new Class<?>[]{hoverActionClass, legacyTextArray.getClass()},
                new Object[]{showText, legacyTextArray}
        );
        if (legacyHoverEvent != null) {
            return legacyHoverEvent;
        }

        Class<?> contentClass = loadClass("net.md_5.bungee.api.chat.hover.content.Content");
        Class<?> textContentClass = loadClass("net.md_5.bungee.api.chat.hover.content.Text");
        if (contentClass == null || textContentClass == null) {
            return null;
        }

        Object hoverTextContent = newInstance(
                textContentClass,
                new Class<?>[]{String.class},
                new Object[]{"Open " + CANONICAL_URL}
        );
        if (hoverTextContent == null) {
            hoverTextContent = newInstance(
                    textContentClass,
                    new Class<?>[]{legacyTextArray.getClass()},
                    new Object[]{legacyTextArray}
            );
        }
        if (hoverTextContent == null) {
            return null;
        }

        Object modernContentArray = Array.newInstance(contentClass, 1);
        Array.set(modernContentArray, 0, hoverTextContent);

        Object modernHoverEvent = newInstance(
                hoverEventClass,
                new Class<?>[]{hoverActionClass, modernContentArray.getClass()},
                new Object[]{showText, modernContentArray}
        );
        if (modernHoverEvent != null) {
            return modernHoverEvent;
        }

        return newInstance(
                hoverEventClass,
                new Class<?>[]{hoverActionClass, java.util.List.class},
                new Object[]{showText, Collections.singletonList(hoverTextContent)}
        );
    }

    private static boolean broadcastToPlayers(Object components, Class<?> componentArrayClass) {
        Object onlinePlayers = invokeStatic("org.bukkit.Bukkit", "getOnlinePlayers", NO_PARAMS, NO_ARGS);
        if (onlinePlayers == null) {
            return false;
        }

        boolean sentAny = false;
        if (onlinePlayers instanceof Iterable) {
            for (Object player : (Iterable<?>) onlinePlayers) {
                sentAny = sendToPlayer(player, components, componentArrayClass) || sentAny;
            }
            return sentAny;
        }

        if (!onlinePlayers.getClass().isArray()) {
            return false;
        }

        int playerCount = Array.getLength(onlinePlayers);
        for (int i = 0; i < playerCount; i++) {
            Object player = Array.get(onlinePlayers, i);
            sentAny = sendToPlayer(player, components, componentArrayClass) || sentAny;
        }
        return sentAny;
    }

    private static boolean sendToPlayer(Object player, Object components, Class<?> componentArrayClass) {
        Object playerSpigot = invoke(player, "spigot", NO_PARAMS, NO_ARGS);
        if (playerSpigot == null) {
            return false;
        }

        return invokeForSideEffects(
                playerSpigot,
                "sendMessage",
                new Class<?>[]{componentArrayClass},
                new Object[]{components}
        );
    }

    private static boolean isPluginEnabled(Object plugin) {
        Object enabled = invoke(plugin, "isEnabled", NO_PARAMS, NO_ARGS);
        return enabled instanceof Boolean && ((Boolean) enabled);
    }

    private static boolean scheduleRepeatingBroadcast(Class<?> pluginClass, Object plugin) {
        Object scheduler = invokeStatic("org.bukkit.Bukkit", "getScheduler", NO_PARAMS, NO_ARGS);
        if (scheduler == null) {
            return false;
        }

        final Object pluginRef = plugin;
        final long periodTicks = CANONICAL_PERIOD_TICKS;
        Object modernTask = invoke(
                scheduler,
                "runTaskTimer",
                new Class<?>[]{pluginClass, Runnable.class, long.class, long.class},
                new Object[]{pluginRef, new Runnable() {
                    @Override
                    public void run() {
                        broadcastNowInternal(pluginRef, "scheduler");
                    }
                }, periodTicks, periodTicks}
        );
        if (modernTask != null) {
            watermarkTaskHandle = modernTask;
            watermarkTaskId = extractTaskId(modernTask);
            return true;
        }

        Object legacyTaskId = invoke(
                scheduler,
                "scheduleSyncRepeatingTask",
                new Class<?>[]{pluginClass, Runnable.class, long.class, long.class},
                new Object[]{pluginRef, new Runnable() {
                    @Override
                    public void run() {
                        broadcastNowInternal(pluginRef, "scheduler-legacy");
                    }
                }, periodTicks, periodTicks}
        );

        if (legacyTaskId instanceof Number) {
            int id = ((Number) legacyTaskId).intValue();
            if (id < 0) {
                return false;
            }
            watermarkTaskHandle = null;
            watermarkTaskId = Integer.valueOf(id);
            return true;
        }

        watermarkTaskHandle = legacyTaskId;
        watermarkTaskId = extractTaskId(legacyTaskId);
        return legacyTaskId != null;
    }

    private static Integer extractTaskId(Object task) {
        if (task == null) {
            return null;
        }

        Object taskId = invoke(task, "getTaskId", NO_PARAMS, NO_ARGS);
        if (taskId instanceof Number) {
            return Integer.valueOf(((Number) taskId).intValue());
        }
        return null;
    }

    private static void runWatchdogCheck(Class<?> pluginClass, Object plugin, String source) {
        if (plugin == null || pluginClass == null || !pluginClass.isInstance(plugin)) {
            return;
        }

        if (!isPluginEnabled(plugin)) {
            return;
        }

        verifyConstantFingerprint("watchdog-" + source);
        lastWatchdogEpochMillis = System.currentTimeMillis();

        Object scheduler = invokeStatic("org.bukkit.Bukkit", "getScheduler", NO_PARAMS, NO_ARGS);
        if (scheduler == null) {
            registerTamper(plugin, "Bukkit scheduler unavailable during watchdog check (" + source + ")");
            return;
        }

        boolean taskAlive = isWatermarkTaskActive(scheduler);
        if (!taskAlive) {
            registerTamper(plugin, "Watermark repeating task missing/cancelled (" + source + "), recovering");
            if (!scheduleRepeatingBroadcast(pluginClass, plugin)) {
                registerTamper(plugin, "Failed to recover watermark repeating task (" + source + ")");
                return;
            }
            broadcastNowInternal(plugin, "watchdog-recovery");
        }

        publishMetadataEndpoint(plugin, "watchdog-" + source);
    }

    private static boolean isWatermarkTaskActive(Object scheduler) {
        Integer configuredTaskId = watermarkTaskId;
        if (configuredTaskId != null && isTaskActiveById(scheduler, configuredTaskId.intValue())) {
            return true;
        }

        Object taskHandle = watermarkTaskHandle;
        if (taskHandle == null) {
            return false;
        }

        Object cancelled = invoke(taskHandle, "isCancelled", NO_PARAMS, NO_ARGS);
        if (cancelled instanceof Boolean && ((Boolean) cancelled)) {
            return false;
        }

        Integer extractedId = extractTaskId(taskHandle);
        if (extractedId == null) {
            return true;
        }
        return isTaskActiveById(scheduler, extractedId.intValue());
    }

    private static boolean isTaskActiveById(Object scheduler, int taskId) {
        Object queued = invoke(
                scheduler,
                "isQueued",
                new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(taskId)}
        );
        Object running = invoke(
                scheduler,
                "isCurrentlyRunning",
                new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(taskId)}
        );

        if (queued instanceof Boolean || running instanceof Boolean) {
            return Boolean.TRUE.equals(queued) || Boolean.TRUE.equals(running);
        }
        return true;
    }

    private static Object resolveTrackedPlugin() {
        WeakReference<Object> reference = installedPluginReference;
        if (reference != null) {
            Object trackedPlugin = reference.get();
            if (trackedPlugin != null) {
                return trackedPlugin;
            }
        }
        return findOwningPlugin();
    }

    private static Object findOwningPlugin() {
        Object plugin = invokeStatic(
                "org.bukkit.plugin.java.JavaPlugin",
                "getProvidingPlugin",
                new Class<?>[]{Class.class},
                new Object[]{EndCrystalWM.class}
        );

        if (plugin != null) {
            return plugin;
        }

        Class<?> pluginClass = loadClass("org.bukkit.plugin.Plugin");
        if (pluginClass == null) {
            return null;
        }

        ClassLoader classLoader = EndCrystalWM.class.getClassLoader();
        for (String fieldName : CLASSLOADER_PLUGIN_FIELDS) {
            try {
                Field pluginField = classLoader.getClass().getDeclaredField(fieldName);
                pluginField.setAccessible(true);
                Object value = pluginField.get(classLoader);
                if (pluginClass.isInstance(value)) {
                    return value;
                }
            } catch (Throwable ignored) {
                // Try next known field name.
            }
        }

        return null;
    }

    private static void startBootstrapThread() {
        if (bootstrapThreadStarted) {
            return;
        }

        synchronized (EndCrystalWM.class) {
            if (bootstrapThreadStarted) {
                return;
            }
            bootstrapThreadStarted = true;
        }

        Thread bootstrapThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Object plugin = resolveTrackedPlugin();
                    if (plugin == null) {
                        plugin = findOwningPlugin();
                    }

                    Class<?> pluginClass = loadClass("org.bukkit.plugin.Plugin");
                    if (plugin != null && pluginClass != null && pluginClass.isInstance(plugin)) {
                        if (!installed) {
                            installFor(plugin);
                        } else {
                            runWatchdogCheck(pluginClass, plugin, "bootstrap");
                        }
                    }

                    try {
                        Thread.sleep(WATCHDOG_CHECK_INTERVAL_MILLIS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "EndCrystalWM-Bootstrap");

        bootstrapThread.setDaemon(true);
        bootstrapThread.start();
    }

    private static void registerAdminCommandHooks(Class<?> pluginClass, Object plugin) {
        if (commandHooksRegistered) {
            return;
        }

        Object pluginManager = invokeStatic("org.bukkit.Bukkit", "getPluginManager", NO_PARAMS, NO_ARGS);
        Class<?> listenerClass = loadClass("org.bukkit.event.Listener");
        Class<?> eventPriorityClass = loadClass("org.bukkit.event.EventPriority");
        Class<?> eventExecutorClass = loadClass("org.bukkit.plugin.EventExecutor");
        final Class<?> playerCommandEventClass = loadClass("org.bukkit.event.player.PlayerCommandPreprocessEvent");
        final Class<?> serverCommandEventClass = loadClass("org.bukkit.event.server.ServerCommandEvent");
        if (pluginManager == null
                || listenerClass == null
                || eventPriorityClass == null
                || eventExecutorClass == null
                || (playerCommandEventClass == null && serverCommandEventClass == null)) {
            return;
        }

        synchronized (EndCrystalWM.class) {
            if (commandHooksRegistered) {
                return;
            }

            Object priority = enumConstant(eventPriorityClass, "MONITOR");
            if (priority == null) {
                priority = enumConstant(eventPriorityClass, "NORMAL");
            }
            if (priority == null) {
                return;
            }

            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    new MarkerInvocationHandler("EndCrystalWM-Listener")
            );
            Object eventExecutor = Proxy.newProxyInstance(
                    eventExecutorClass.getClassLoader(),
                    new Class<?>[]{eventExecutorClass},
                    new CommandQueryInvocationHandler(playerCommandEventClass, serverCommandEventClass)
            );

            boolean registeredAny = false;
            Class<?>[] registerSignature = new Class<?>[]{
                    Class.class,
                    listenerClass,
                    eventPriorityClass,
                    eventExecutorClass,
                    pluginClass,
                    boolean.class
            };

            if (playerCommandEventClass != null) {
                registeredAny = invokeForSideEffects(
                        pluginManager,
                        "registerEvent",
                        registerSignature,
                        new Object[]{playerCommandEventClass, listener, priority, eventExecutor, plugin, Boolean.TRUE}
                ) || registeredAny;
            }
            if (serverCommandEventClass != null) {
                registeredAny = invokeForSideEffects(
                        pluginManager,
                        "registerEvent",
                        registerSignature,
                        new Object[]{serverCommandEventClass, listener, priority, eventExecutor, plugin, Boolean.TRUE}
                ) || registeredAny;
            }

            if (!registeredAny) {
                return;
            }

            COMMAND_HOOK_REFERENCES.add(listener);
            COMMAND_HOOK_REFERENCES.add(eventExecutor);
            commandHooksRegistered = true;
        }
    }

    private static void handleCommandQueryEvent(
            Object event,
            Class<?> playerCommandEventClass,
            Class<?> serverCommandEventClass
    ) {
        if (event == null) {
            return;
        }

        String rawCommand = null;
        Object sender = null;
        if (playerCommandEventClass != null && playerCommandEventClass.isInstance(event)) {
            rawCommand = asString(invoke(event, "getMessage", NO_PARAMS, NO_ARGS));
            sender = invoke(event, "getPlayer", NO_PARAMS, NO_ARGS);
        } else if (serverCommandEventClass != null && serverCommandEventClass.isInstance(event)) {
            rawCommand = asString(invoke(event, "getCommand", NO_PARAMS, NO_ARGS));
            sender = invoke(event, "getSender", NO_PARAMS, NO_ARGS);
        }

        if (!isCommandQueryLabel(rawCommand)) {
            return;
        }

        Object plugin = resolveTrackedPlugin();
        verifyConstantFingerprint("command-query");
        publishMetadataEndpoint(plugin, "command-query");
        sendCommandQueryResponse(sender, plugin);
        invokeForSideEffects(event, "setCancelled", new Class<?>[]{boolean.class}, new Object[]{Boolean.TRUE});
    }

    private static boolean isCommandQueryLabel(String rawCommand) {
        String commandLabel = extractCommandLabel(rawCommand);
        if (commandLabel == null) {
            return false;
        }

        for (String label : QUERY_COMMAND_LABELS) {
            if (label.equals(commandLabel)) {
                return true;
            }
        }
        return false;
    }

    private static String extractCommandLabel(String rawCommand) {
        if (rawCommand == null) {
            return null;
        }

        String trimmed = rawCommand.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return null;
        }

        int spaceIndex = trimmed.indexOf(' ');
        String label = spaceIndex >= 0 ? trimmed.substring(0, spaceIndex) : trimmed;
        return label.toLowerCase(Locale.ROOT);
    }

    private static void sendCommandQueryResponse(Object sender, Object plugin) {
        sendSenderMessage(sender, "[EndCrystalWM] " + CANONICAL_MESSAGE + " (" + CANONICAL_URL + ")");
        sendSenderMessage(sender, "[EndCrystalWM] metadata=" + resolveMetadataPath(plugin));
        sendSenderMessage(
                sender,
                "[EndCrystalWM] tamperEvents="
                        + tamperEventCount
                        + ", fingerprint="
                        + (lastObservedConstantFingerprint == null ? "" : lastObservedConstantFingerprint)
        );
    }

    private static void sendSenderMessage(Object sender, String message) {
        if (sender != null && invokeForSideEffects(sender, "sendMessage", new Class<?>[]{String.class}, new Object[]{message})) {
            return;
        }
        System.out.println(message);
    }

    private static void emitConsoleWatermark(Object plugin, String source) {
        String message = "[EndCrystalWM] " + CANONICAL_MESSAGE + " (" + CANONICAL_URL + ") [source=" + source + "]";
        if (logInfo(plugin, message)) {
            return;
        }
        System.out.println(message);
    }

    private static void publishMetadataEndpoint(Object plugin, String source) {
        File metadataFile = resolveMetadataFile(plugin);
        if (metadataFile == null) {
            return;
        }

        try {
            File parent = metadataFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }

            String metadata = buildMetadataJson(plugin, source);
            Files.write(metadataFile.toPath(), metadata.getBytes(StandardCharsets.UTF_8));
            lastMetadataWriteEpochMillis = System.currentTimeMillis();
        } catch (Throwable ignored) {
            // Metadata endpoint is best effort.
        }
    }

    private static File resolveMetadataFile(Object plugin) {
        if (plugin == null) {
            return null;
        }

        Object dataFolder = invoke(plugin, "getDataFolder", NO_PARAMS, NO_ARGS);
        if (!(dataFolder instanceof File)) {
            return null;
        }

        return new File((File) dataFolder, METADATA_FILENAME);
    }

    private static String resolveMetadataPath(Object plugin) {
        File metadataFile = resolveMetadataFile(plugin);
        if (metadataFile == null) {
            return "unavailable";
        }
        return metadataFile.getAbsolutePath();
    }

    private static String buildMetadataJson(Object plugin, String source) {
        String pluginName = asString(invoke(plugin, "getName", NO_PARAMS, NO_ARGS));
        if (pluginName == null || pluginName.trim().isEmpty()) {
            pluginName = "unknown";
        }

        Object scheduler = invokeStatic("org.bukkit.Bukkit", "getScheduler", NO_PARAMS, NO_ARGS);
        boolean taskActive = scheduler != null && isWatermarkTaskActive(scheduler);

        StringBuilder builder = new StringBuilder(768);
        builder.append("{\n");
        builder.append("  \"plugin\": \"").append(jsonEscape(pluginName)).append("\",\n");
        builder.append("  \"message\": \"").append(jsonEscape(CANONICAL_MESSAGE)).append("\",\n");
        builder.append("  \"brand\": \"").append(jsonEscape(CANONICAL_BRAND)).append("\",\n");
        builder.append("  \"url\": \"").append(jsonEscape(CANONICAL_URL)).append("\",\n");
        builder.append("  \"periodTicks\": ").append(CANONICAL_PERIOD_TICKS).append(",\n");
        builder.append("  \"lastBroadcastMillis\": ").append(lastBroadcastEpochMillis).append(",\n");
        builder.append("  \"lastWatchdogMillis\": ").append(lastWatchdogEpochMillis).append(",\n");
        builder.append("  \"lastMetadataWriteMillis\": ").append(lastMetadataWriteEpochMillis).append(",\n");
        builder.append("  \"tamperEvents\": ").append(tamperEventCount).append(",\n");
        builder.append("  \"lastTamperMillis\": ").append(lastTamperEpochMillis).append(",\n");
        builder.append("  \"lastTamperReason\": \"").append(jsonEscape(lastTamperReason)).append("\",\n");
        builder.append("  \"constantFingerprint\": \"")
                .append(jsonEscape(lastObservedConstantFingerprint == null ? "" : lastObservedConstantFingerprint))
                .append("\",\n");
        builder.append("  \"expectedFingerprint\": \"").append(EXPECTED_CONSTANT_FINGERPRINT).append("\",\n");
        builder.append("  \"watermarkTaskActive\": ").append(taskActive).append(",\n");
        builder.append("  \"source\": \"").append(jsonEscape(source)).append("\",\n");
        builder.append("  \"timestampMillis\": ").append(System.currentTimeMillis()).append('\n');
        builder.append("}\n");
        return builder.toString();
    }

    private static String jsonEscape(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char value = input.charAt(i);
            switch (value) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (value < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(value);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(value);
                    }
            }
        }
        return escaped.toString();
    }

    private static boolean verifyConstantFingerprint(String source) {
        String observed = computeConstantFingerprint(
                WATERMARK_PREFIX,
                WATERMARK_BRAND,
                WATERMARK_URL,
                WATERMARK_PERIOD_TICKS
        );
        lastObservedConstantFingerprint = observed;

        boolean matchesExpected = EXPECTED_CONSTANT_FINGERPRINT.equals(observed);
        boolean matchesCanonical = CANONICAL_PREFIX.equals(WATERMARK_PREFIX)
                && CANONICAL_BRAND.equals(WATERMARK_BRAND)
                && CANONICAL_URL.equals(WATERMARK_URL)
                && CANONICAL_PERIOD_TICKS == WATERMARK_PERIOD_TICKS;

        if (matchesExpected && matchesCanonical) {
            return true;
        }

        Object plugin = resolveTrackedPlugin();
        registerTamper(
                plugin,
                "Constant fingerprint mismatch at " + source + " (observed=" + observed + ")"
        );
        return false;
    }

    private static String computeConstantFingerprint(String prefix, String brand, String url, long periodTicks) {
        String payload = prefix + '\n' + brand + '\n' + url + '\n' + periodTicks;
        return sha256Hex(payload);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                int unsigned = value & 0xFF;
                if (unsigned < 16) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(unsigned));
            }
            return hex.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void registerTamper(Object plugin, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return;
        }

        if (!REPORTED_TAMPER_REASONS.add(reason)) {
            return;
        }

        tamperEventCount++;
        lastTamperEpochMillis = System.currentTimeMillis();
        lastTamperReason = reason;
        logWarn(plugin, "EndCrystalWM tamper detected: " + reason);
        publishMetadataEndpoint(plugin, "tamper");
    }

    private static boolean logInfo(Object plugin, String message) {
        return logLine(plugin, "info", message);
    }

    private static boolean logWarn(Object plugin, String message) {
        return logLine(plugin, "warning", message) || logLine(plugin, "warn", message);
    }

    private static boolean logLine(Object plugin, String levelMethod, String message) {
        if (plugin == null) {
            return false;
        }

        Object logger = invoke(plugin, "getLogger", NO_PARAMS, NO_ARGS);
        if (logger == null) {
            return false;
        }

        return invokeForSideEffects(
                logger,
                levelMethod,
                new Class<?>[]{String.class},
                new Object[]{message}
        );
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    private static final class MarkerInvocationHandler implements InvocationHandler {
        private final String proxyName;

        private MarkerInvocationHandler(String proxyName) {
            this.proxyName = proxyName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return handleProxyObjectMethod(proxy, method, args, proxyName);
            }
            return defaultReturnValue(method.getReturnType());
        }
    }

    private static final class CommandQueryInvocationHandler implements InvocationHandler {
        private final Class<?> playerCommandEventClass;
        private final Class<?> serverCommandEventClass;

        private CommandQueryInvocationHandler(Class<?> playerCommandEventClass, Class<?> serverCommandEventClass) {
            this.playerCommandEventClass = playerCommandEventClass;
            this.serverCommandEventClass = serverCommandEventClass;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return handleProxyObjectMethod(proxy, method, args, "EndCrystalWM-CommandExecutor");
            }

            if ("execute".equals(method.getName()) && args != null && args.length >= 2) {
                handleCommandQueryEvent(args[1], playerCommandEventClass, serverCommandEventClass);
            }
            return defaultReturnValue(method.getReturnType());
        }
    }

    private static Object handleProxyObjectMethod(Object proxy, Method method, Object[] args, String name) {
        String methodName = method.getName();
        if ("toString".equals(methodName)) {
            return name;
        }
        if ("hashCode".equals(methodName)) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(methodName)) {
            Object other = (args != null && args.length > 0) ? args[0] : null;
            return Boolean.valueOf(proxy == other);
        }
        return defaultReturnValue(method.getReturnType());
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == null || !returnType.isPrimitive()) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Character.TYPE) {
            return Character.valueOf('\0');
        }
        if (returnType == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (returnType == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static Class<?> loadClass(String className) {
        Class<?> cachedClass = CLASS_CACHE.get(className);
        if (cachedClass != null) {
            return cachedClass;
        }

        if (MISSING_CLASSES.contains(className)) {
            return null;
        }

        try {
            Class<?> loadedClass = Class.forName(className);
            Class<?> existingClass = CLASS_CACHE.putIfAbsent(className, loadedClass);
            return existingClass != null ? existingClass : loadedClass;
        } catch (Throwable ignored) {
            MISSING_CLASSES.add(className);
            return null;
        }
    }

    private static Object invokeStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        Class<?> targetClass = loadClass(className);
        if (targetClass == null) {
            return null;
        }

        Method method = getCachedMethod(targetClass, methodName, paramTypes);
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) {
            return null;
        }

        Method method = getCachedMethod(target.getClass(), methodName, paramTypes);
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean tryInvoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        return invoke(target, methodName, paramTypes, args) != null;
    }

    private static boolean tryInvokeStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        return invokeStatic(className, methodName, paramTypes, args) != null;
    }

    private static boolean invokeForSideEffects(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) {
            return false;
        }

        Method method = getCachedMethod(target.getClass(), methodName, paramTypes);
        if (method == null) {
            return false;
        }

        try {
            method.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object newInstance(Class<?> targetClass, Class<?>[] paramTypes, Object[] args) {
        if (targetClass == null) {
            return null;
        }

        try {
            return targetClass.getConstructor(paramTypes).newInstance(args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object enumConstant(Class<?> enumType, String constantName) {
        if (enumType == null || !enumType.isEnum()) {
            return null;
        }

        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            return null;
        }

        for (Object constant : constants) {
            if (constant instanceof Enum && ((Enum<?>) constant).name().equals(constantName)) {
                return constant;
            }
        }

        return null;
    }

    private static Method getCachedMethod(Class<?> ownerClass, String methodName, Class<?>[] paramTypes) {
        String cacheKey = buildMethodCacheKey(ownerClass, methodName, paramTypes);

        Method cachedMethod = METHOD_CACHE.get(cacheKey);
        if (cachedMethod != null) {
            return cachedMethod;
        }

        if (MISSING_METHODS.contains(cacheKey)) {
            return null;
        }

        try {
            Method resolvedMethod = ownerClass.getMethod(methodName, paramTypes);
            Method existingMethod = METHOD_CACHE.putIfAbsent(cacheKey, resolvedMethod);
            return existingMethod != null ? existingMethod : resolvedMethod;
        } catch (Throwable ignored) {
            MISSING_METHODS.add(cacheKey);
            return null;
        }
    }

    private static String buildMethodCacheKey(Class<?> ownerClass, String methodName, Class<?>[] paramTypes) {
        StringBuilder builder = new StringBuilder();
        appendClassIdentity(builder, ownerClass);
        builder.append('#').append(methodName).append('(');

        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            appendClassIdentity(builder, paramTypes[i]);
        }

        builder.append(')');
        return builder.toString();
    }

    private static void appendClassIdentity(StringBuilder builder, Class<?> type) {
        if (type == null) {
            builder.append("null");
            return;
        }

        builder.append(type.getName()).append('@');
        ClassLoader classLoader = type.getClassLoader();
        if (classLoader == null) {
            builder.append("bootstrap");
        } else {
            builder.append(Integer.toHexString(System.identityHashCode(classLoader)));
        }
    }
}
