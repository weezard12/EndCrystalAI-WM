package me.weezard12.endCrystalWM;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class EndCrystalWM {
    public static final String WATERMARK_PREFIX = "This plugin was generated with ";
    public static final String WATERMARK_BRAND = "End Crystal AI";
    public static final String WATERMARK_URL = "https://endcrystal.ai";
    public static final String WATERMARK_MESSAGE = WATERMARK_PREFIX + WATERMARK_BRAND;
    public static final long WATERMARK_PERIOD_TICKS = 20L * 30L;

    private static final Class<?>[] NO_PARAMS = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];
    private static final Set<Object> INSTALLED_PLUGINS =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    private static volatile boolean bootstrapThreadStarted;

    static {
        install();
        startBootstrapThread();
    }

    private EndCrystalWM() {
    }

    public static void install() {
        Object plugin = findOwningPlugin();
        if (plugin != null) {
            installFor(plugin);
        }
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
            if (INSTALLED_PLUGINS.contains(plugin)) {
                return true;
            }

            if (!isPluginEnabled(plugin)) {
                return false;
            }

            if (!scheduleRepeatingBroadcast(pluginClass, plugin)) {
                return false;
            }

            INSTALLED_PLUGINS.add(plugin);
            return true;
        }
    }

    public static void broadcastNow() {
        if (broadcastRichWatermark()) {
            return;
        }

        String legacyWatermark = WATERMARK_PREFIX + "\u00A75" + WATERMARK_BRAND + "\u00A7r";
        if (tryInvokeStatic(
                "org.bukkit.Bukkit",
                "broadcastMessage",
                new Class<?>[]{String.class},
                new Object[]{legacyWatermark}
        )) {
            return;
        }

        Object server = invokeStatic("org.bukkit.Bukkit", "getServer", NO_PARAMS, NO_ARGS);
        if (server != null) {
            tryInvoke(server, "broadcastMessage", new Class<?>[]{String.class}, new Object[]{legacyWatermark});
        }
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
                new Object[]{WATERMARK_PREFIX}
        );
        Object brandComponent = newInstance(
                textComponentClass,
                new Class<?>[]{String.class},
                new Object[]{WATERMARK_BRAND}
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
                new Object[]{openUrl, WATERMARK_URL}
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
                new Object[]{"Open " + WATERMARK_URL}
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
                new Object[]{"Open " + WATERMARK_URL}
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

        if (tryInvoke(
                scheduler,
                "runTaskTimer",
                new Class<?>[]{pluginClass, Runnable.class, long.class, long.class},
                new Object[]{plugin, new Runnable() {
                    @Override
                    public void run() {
                        broadcastNow();
                    }
                }, WATERMARK_PERIOD_TICKS, WATERMARK_PERIOD_TICKS}
        )) {
            return true;
        }

        Object legacyTaskId = invoke(
                scheduler,
                "scheduleSyncRepeatingTask",
                new Class<?>[]{pluginClass, Runnable.class, long.class, long.class},
                new Object[]{plugin, new Runnable() {
                    @Override
                    public void run() {
                        broadcastNow();
                    }
                }, WATERMARK_PERIOD_TICKS, WATERMARK_PERIOD_TICKS}
        );

        if (legacyTaskId instanceof Integer) {
            return ((Integer) legacyTaskId) >= 0;
        }

        return legacyTaskId != null;
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

        ClassLoader classLoader = EndCrystalWM.class.getClassLoader();
        try {
            Field pluginField = classLoader.getClass().getDeclaredField("plugin");
            pluginField.setAccessible(true);
            return pluginField.get(classLoader);
        } catch (Throwable ignored) {
            return null;
        }
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
                long deadline = System.currentTimeMillis() + 120_000L;
                while (System.currentTimeMillis() < deadline) {
                    Object plugin = findOwningPlugin();
                    if (plugin != null && installFor(plugin)) {
                        return;
                    }

                    try {
                        Thread.sleep(250L);
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

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        Class<?> targetClass = loadClass(className);
        if (targetClass == null) {
            return null;
        }

        try {
            Method method = targetClass.getMethod(methodName, paramTypes);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName, paramTypes);
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

        try {
            Method method = target.getClass().getMethod(methodName, paramTypes);
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
}
