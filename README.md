# EndCrystalWM

Shadeable Paper/Bukkit watermark library + optional Gradle bytecode injector.

It broadcasts this message globally in chat every 30 seconds:

`This plugin was generated for FREE using End Crystal AI`

- `End Crystal AI` is sent as a purple clickable text segment.
- Hover text prompts opening `https://endcrystal.ai`.

## JitPack

```groovy
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.weezard12:EndCrystalWM:<tag>")
}
```

## No Java-Code Setup (Auto Inject onEnable)

Use this when you want zero Java changes in your host plugin.

```groovy
buildscript {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.github.weezard12:EndCrystalWM:<tag>")
    }
}

plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

apply plugin: "me.weezard12.endcrystalwm-injector"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.weezard12:EndCrystalWM:<tag>")
}
```

The injector automatically patches your output jar (`jar` and `shadowJar`) by inserting:

`EndCrystalWM.installFor(this);`

into your plugin main class `onEnable()`.

## Injector Options

```groovy
endCrystalWmInjector {
    enabled = true
    // Optional: override plugin main class instead of reading plugin.yml
    // mainClassOverride = "com.example.MyPlugin"

    // Optional: override owner in case of custom relocation
    // injectorOwnerInternalName = "my/relocated/path/EndCrystalWM"

    // Defaults are true:
    // failOnMissingPluginYml = true
    // failOnMissingMainClass = true
}
```

## Runtime Behavior

- When injection is enabled, no host Java code changes are required.
- The injected call is wrapped in `try/catch Throwable` so host plugin startup is not hard-crashed by missing classes.
- Duplicate injection is skipped automatically.
