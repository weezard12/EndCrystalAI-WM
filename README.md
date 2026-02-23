# EndCrystalWM

Shadeable Paper/Bukkit watermark library.

It broadcasts this message globally in chat every 30 seconds:

`This plugin was generated with End Crystal AI`

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

If you use the Shadow plugin:

```groovy
dependencies {
    implementation("com.github.weezard12:EndCrystalWM:<tag>")
}
```

## Runtime Behavior

- The library tries to auto-bootstrap when its class is loaded.
- Paper only executes your plugin main class from `plugin.yml`, so a pure shade-only setup cannot be 100% guaranteed without at least one load trigger.
- For guaranteed startup on every Paper/Bukkit version, call this once in your plugin `onEnable`:

```java
EndCrystalWM.installFor(this);
```
