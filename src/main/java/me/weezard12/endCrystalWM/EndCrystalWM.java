package me.weezard12.endCrystalWM;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class EndCrystalWM {
    public static final String WATERMARK_MESSAGE = "This plugin was generated with End Crystal AI";
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
        if (tryInvokeStatic(
                "org.bukkit.Bukkit",
                "broadcastMessage",
                new Class<?>[]{String.class},
                new Object[]{WATERMARK_MESSAGE}
        )) {
            return;
        }

        Object server = invokeStatic("org.bukkit.Bukkit", "getServer", NO_PARAMS, NO_ARGS);
        if (server != null) {
            tryInvoke(server, "broadcastMessage", new Class<?>[]{String.class}, new Object[]{WATERMARK_MESSAGE});
        }
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
}
