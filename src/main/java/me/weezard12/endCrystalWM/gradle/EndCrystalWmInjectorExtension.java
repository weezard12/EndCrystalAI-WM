package me.weezard12.endCrystalWM.gradle;

import org.gradle.api.provider.Property;

public abstract class EndCrystalWmInjectorExtension {
    public abstract Property<Boolean> getEnabled();

    public abstract Property<String> getMainClassOverride();

    public abstract Property<String> getInjectorOwnerInternalName();

    public abstract Property<String> getWatermarkLanguage();

    public abstract Property<Boolean> getFailOnMissingPluginYml();

    public abstract Property<Boolean> getFailOnMissingMainClass();

    public abstract Property<Boolean> getAutoAddRuntimeDependency();

    public abstract Property<String> getRuntimeDependencyNotation();

    public abstract Property<String> getRuntimeConfiguration();
}
