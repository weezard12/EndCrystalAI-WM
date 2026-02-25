package me.weezard12.endCrystalWM.gradle;

import org.gradle.api.provider.Property;

public abstract class EndCrystalWmInjectorExtension {
    public abstract Property<Boolean> getEnabled();

    public abstract Property<String> getMainClassOverride();

    public abstract Property<String> getInjectorOwnerInternalName();

    public abstract Property<Boolean> getFailOnMissingPluginYml();

    public abstract Property<Boolean> getFailOnMissingMainClass();
}
