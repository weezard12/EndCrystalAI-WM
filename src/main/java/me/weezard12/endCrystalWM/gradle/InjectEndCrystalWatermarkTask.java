package me.weezard12.endCrystalWM.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DisableCachingByDefault(because = "Mutates an archive file in place.")
public abstract class InjectEndCrystalWatermarkTask extends DefaultTask {
    private static final Pattern MAIN_PATTERN = Pattern.compile("^main\\s*:\\s*(.+)$");
    private static final int CLASS_READER_FLAGS = ClassReader.EXPAND_FRAMES;
    private static final int CLASS_WRITER_FLAGS = ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS;

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getTargetJar();

    @Input
    public abstract Property<String> getInjectorOwnerInternalName();

    @Input
    @Optional
    public abstract Property<String> getWatermarkLanguage();

    @Input
    @Optional
    public abstract Property<String> getMainClassOverride();

    @Input
    public abstract Property<Boolean> getFailOnMissingPluginYml();

    @Input
    public abstract Property<Boolean> getFailOnMissingMainClass();

    @TaskAction
    public void inject() {
        File targetJarFile = getTargetJar().getAsFile().getOrNull();
        if (targetJarFile == null || !targetJarFile.isFile()) {
            getLogger().lifecycle("EndCrystalWM injector: target jar is unavailable, skipping");
            return;
        }

        String configuredInjectorOwner = normalizeOwner(getInjectorOwnerInternalName().get());
        String configuredWatermarkLanguage = normalizeLanguageTag(getWatermarkLanguage().getOrNull());

        JarSnapshot snapshot;
        try {
            snapshot = readJar(targetJarFile.toPath());
        } catch (IOException exception) {
            throw new GradleException("EndCrystalWM injector: failed reading jar " + targetJarFile, exception);
        }

        String injectorOwner = resolveInjectorOwner(snapshot.entriesByName, configuredInjectorOwner);
        String mainClass = resolveMainClass(snapshot.entriesByName);
        if (mainClass == null) {
            return;
        }

        String mainClassEntryName = mainClass.replace('.', '/') + ".class";
        JarEntryData mainClassEntry = snapshot.entriesByName.get(mainClassEntryName);
        if (mainClassEntry == null) {
            String message = "EndCrystalWM injector: main class '" + mainClass + "' was not found in " + targetJarFile;
            if (Boolean.TRUE.equals(getFailOnMissingMainClass().getOrElse(Boolean.TRUE))) {
                throw new GradleException(message);
            }
            getLogger().warn(message + " (skipping)");
            return;
        }

        byte[] patchedClassBytes = patchMainClass(
                mainClassEntry.bytes,
                injectorOwner,
                mainClass,
                configuredWatermarkLanguage
        );
        if (patchedClassBytes == null) {
            getLogger().lifecycle("EndCrystalWM injector: {} already contains an install call, skipping", mainClass);
            return;
        }

        mainClassEntry.bytes = patchedClassBytes;
        try {
            writeJar(targetJarFile.toPath(), snapshot.entries);
        } catch (IOException exception) {
            throw new GradleException("EndCrystalWM injector: failed writing patched jar " + targetJarFile, exception);
        }

        getLogger().lifecycle("EndCrystalWM injector: patched {} in {}", mainClass, targetJarFile.getName());
    }

    private String resolveMainClass(Map<String, JarEntryData> entriesByName) {
        String override = trimToNull(getMainClassOverride().getOrNull());
        if (override != null) {
            return normalizeMainClassName(override);
        }

        String mainClass = extractMainClass(entriesByName.get("plugin.yml"));
        if (mainClass != null) {
            return mainClass;
        }

        mainClass = extractMainClass(entriesByName.get("paper-plugin.yml"));
        if (mainClass != null) {
            return mainClass;
        }

        boolean failOnMissingPluginYml = Boolean.TRUE.equals(getFailOnMissingPluginYml().getOrElse(Boolean.TRUE));
        boolean failOnMissingMainClass = Boolean.TRUE.equals(getFailOnMissingMainClass().getOrElse(Boolean.TRUE));

        if (entriesByName.containsKey("plugin.yml") || entriesByName.containsKey("paper-plugin.yml")) {
            String message = "EndCrystalWM injector: no 'main' key found in plugin descriptor";
            if (failOnMissingMainClass) {
                throw new GradleException(message);
            }
            getLogger().warn(message + " (skipping)");
            return null;
        }

        String message = "EndCrystalWM injector: plugin.yml or paper-plugin.yml is missing";
        if (failOnMissingPluginYml) {
            throw new GradleException(message);
        }
        getLogger().warn(message + " (skipping)");
        return null;
    }

    private String resolveInjectorOwner(Map<String, JarEntryData> entriesByName, String configuredOwner) {
        if (entriesByName.containsKey(configuredOwner + ".class")) {
            return configuredOwner;
        }

        List<String> candidates = new ArrayList<String>();
        for (String entryName : entriesByName.keySet()) {
            if (entryName.endsWith("/EndCrystalWM.class") || "EndCrystalWM.class".equals(entryName)) {
                candidates.add(entryName.substring(0, entryName.length() - ".class".length()));
            }
        }

        if (candidates.isEmpty()) {
            getLogger().warn(
                    "EndCrystalWM injector: '{}' was not found in the archive, keeping configured owner",
                    configuredOwner
            );
            return configuredOwner;
        }

        if (candidates.size() == 1) {
            String detectedOwner = candidates.get(0);
            getLogger().lifecycle(
                    "EndCrystalWM injector: detected relocated owner '{}' (configured '{}')",
                    detectedOwner,
                    configuredOwner
            );
            return detectedOwner;
        }

        String preferredOwner = null;
        for (String candidate : candidates) {
            if (candidate.contains("/endCrystalWM/")) {
                preferredOwner = candidate;
                break;
            }
        }

        if (preferredOwner != null) {
            getLogger().lifecycle(
                    "EndCrystalWM injector: using preferred owner '{}' from {} candidates",
                    preferredOwner,
                    Integer.valueOf(candidates.size())
            );
            return preferredOwner;
        }

        getLogger().warn(
                "EndCrystalWM injector: found multiple EndCrystalWM owners {}, using configured '{}'",
                candidates,
                configuredOwner
        );
        return configuredOwner;
    }

    private byte[] patchMainClass(
            byte[] classBytes,
            String injectorOwner,
            String mainClassName,
            String watermarkLanguage
    ) {
        ClassNode classNode = new ClassNode();
        ClassReader classReader = new ClassReader(classBytes);
        classReader.accept(classNode, CLASS_READER_FLAGS);

        MethodNode onEnable = findOnEnableMethod(classNode);
        if (onEnable != null && containsInstallCall(onEnable, injectorOwner)) {
            return null;
        }

        if (onEnable == null) {
            onEnable = createOnEnableMethod(classNode, injectorOwner, watermarkLanguage);
            classNode.methods.add(onEnable);
        } else {
            if ((onEnable.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                throw new GradleException("EndCrystalWM injector: " + mainClassName + "#onEnable is not concrete");
            }
            injectIntoExistingOnEnable(onEnable, injectorOwner, watermarkLanguage);
        }

        ClassWriter writer = new SafeClassWriter(classReader, CLASS_WRITER_FLAGS, getClass().getClassLoader());
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private MethodNode findOnEnableMethod(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if ("onEnable".equals(method.name) && "()V".equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private boolean containsInstallCall(MethodNode onEnableMethod, String injectorOwner) {
        for (AbstractInsnNode instruction = onEnableMethod.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) {
                continue;
            }

            MethodInsnNode methodInstruction = (MethodInsnNode) instruction;
            boolean matchingSignature =
                    ("installFor".equals(methodInstruction.name) && "(Ljava/lang/Object;)Z".equals(methodInstruction.desc))
                            || ("installFor".equals(methodInstruction.name)
                            && "(Ljava/lang/Object;Ljava/lang/String;)Z".equals(methodInstruction.desc))
                            || ("install".equals(methodInstruction.name) && "()V".equals(methodInstruction.desc));
            if (!matchingSignature) {
                continue;
            }

            if (injectorOwner.equals(methodInstruction.owner) || methodInstruction.owner.endsWith("/EndCrystalWM")) {
                return true;
            }
        }
        return false;
    }

    private void injectIntoExistingOnEnable(MethodNode onEnableMethod, String injectorOwner, String watermarkLanguage) {
        InjectionBlock block = createInjectionBlock(injectorOwner, watermarkLanguage);
        onEnableMethod.instructions.insert(block.instructions);
        addTryCatchBlocks(onEnableMethod, block);
    }

    private MethodNode createOnEnableMethod(ClassNode classNode, String injectorOwner, String watermarkLanguage) {
        MethodNode onEnableMethod = new MethodNode(Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null);

        if (classNode.superName != null && !"java/lang/Object".equals(classNode.superName)) {
            onEnableMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            onEnableMethod.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    classNode.superName,
                    "onEnable",
                    "()V",
                    false
            ));
        }

        InjectionBlock block = createInjectionBlock(injectorOwner, watermarkLanguage);
        onEnableMethod.instructions.add(block.instructions);
        onEnableMethod.tryCatchBlocks.addAll(block.tryCatchBlocks);
        onEnableMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        return onEnableMethod;
    }

    private void addTryCatchBlocks(MethodNode methodNode, InjectionBlock block) {
        if (block.tryCatchBlocks.isEmpty()) {
            return;
        }

        for (int index = block.tryCatchBlocks.size() - 1; index >= 0; index--) {
            methodNode.tryCatchBlocks.add(0, block.tryCatchBlocks.get(index));
        }
    }

    private InjectionBlock createInjectionBlock(String injectorOwner, String watermarkLanguage) {
        InsnList instructions = new InsnList();
        List<TryCatchBlockNode> tryCatchBlocks = new ArrayList<TryCatchBlockNode>();

        LabelNode languageTryStart = new LabelNode();
        LabelNode languageTryEnd = new LabelNode();
        LabelNode languageCatchHandler = new LabelNode();
        LabelNode afterLanguageCatch = new LabelNode();

        instructions.add(languageTryStart);
        instructions.add(new LdcInsnNode(watermarkLanguage));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                injectorOwner,
                "configureWatermarkLanguage",
                "(Ljava/lang/String;)V",
                false
        ));
        instructions.add(languageTryEnd);
        instructions.add(new JumpInsnNode(Opcodes.GOTO, afterLanguageCatch));
        instructions.add(languageCatchHandler);
        instructions.add(new InsnNode(Opcodes.POP));
        instructions.add(afterLanguageCatch);

        tryCatchBlocks.add(new TryCatchBlockNode(
                languageTryStart,
                languageTryEnd,
                languageCatchHandler,
                "java/lang/Throwable"
        ));

        LabelNode installTryStart = new LabelNode();
        LabelNode installTryEnd = new LabelNode();
        LabelNode installCatchHandler = new LabelNode();
        LabelNode afterInstallCatch = new LabelNode();

        instructions.add(installTryStart);
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                injectorOwner,
                "installFor",
                "(Ljava/lang/Object;)Z",
                false
        ));
        instructions.add(new InsnNode(Opcodes.POP));
        instructions.add(installTryEnd);
        instructions.add(new JumpInsnNode(Opcodes.GOTO, afterInstallCatch));
        instructions.add(installCatchHandler);
        instructions.add(new InsnNode(Opcodes.POP));
        instructions.add(afterInstallCatch);

        tryCatchBlocks.add(new TryCatchBlockNode(
                installTryStart,
                installTryEnd,
                installCatchHandler,
                "java/lang/Throwable"
        ));

        return new InjectionBlock(instructions, tryCatchBlocks);
    }

    private JarSnapshot readJar(Path jarPath) throws IOException {
        List<JarEntryData> entries = new ArrayList<JarEntryData>();
        Map<String, JarEntryData> entriesByName = new LinkedHashMap<String, JarEntryData>();

        JarFile jarFile = new JarFile(jarPath.toFile());
        try {
            Enumeration<JarEntry> iterator = jarFile.entries();
            while (iterator.hasMoreElements()) {
                JarEntry jarEntry = iterator.nextElement();
                byte[] data = jarEntry.isDirectory() ? new byte[0] : readAllBytes(jarFile.getInputStream(jarEntry));
                JarEntryData entryData = new JarEntryData(jarEntry.getName(), data, jarEntry.getTime(), jarEntry.isDirectory());
                entries.add(entryData);
                entriesByName.put(jarEntry.getName(), entryData);
            }
        } finally {
            jarFile.close();
        }

        return new JarSnapshot(entries, entriesByName);
    }

    private void writeJar(Path targetJar, List<JarEntryData> entries) throws IOException {
        Path jarDirectory = targetJar.getParent();
        if (jarDirectory == null) {
            jarDirectory = targetJar.toAbsolutePath().getParent();
        }

        Path temporaryJar = Files.createTempFile(jarDirectory, "endcrystalwm-inject-", ".jar");
        try {
            JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporaryJar));
            try {
                for (JarEntryData entry : entries) {
                    JarEntry newEntry = new JarEntry(entry.name);
                    if (entry.time >= 0L) {
                        newEntry.setTime(entry.time);
                    }
                    output.putNextEntry(newEntry);
                    if (!entry.directory && entry.bytes.length > 0) {
                        output.write(entry.bytes);
                    }
                    output.closeEntry();
                }
            } finally {
                output.close();
            }

            try {
                Files.move(temporaryJar, targetJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }

    private String extractMainClass(JarEntryData descriptorEntry) {
        if (descriptorEntry == null || descriptorEntry.bytes.length == 0) {
            return null;
        }

        String descriptor = new String(descriptorEntry.bytes, StandardCharsets.UTF_8);
        String[] lines = descriptor.split("\\r?\\n");
        for (String line : lines) {
            String withoutComments = stripYamlComment(line).trim();
            if (withoutComments.isEmpty()) {
                continue;
            }

            Matcher matcher = MAIN_PATTERN.matcher(withoutComments);
            if (!matcher.matches()) {
                continue;
            }

            String mainClass = trimToNull(unquote(matcher.group(1).trim()));
            if (mainClass != null) {
                return normalizeMainClassName(mainClass);
            }
        }
        return null;
    }

    private String stripYamlComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        StringBuilder builder = new StringBuilder(line.length());

        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '#' && !inSingleQuote && !inDoubleQuote) {
                break;
            }

            builder.append(current);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (current == '\\') {
                escaped = true;
                continue;
            }

            if (current == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (current == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
        }

        return builder.toString();
    }

    private String unquote(String value) {
        if (value.length() < 2) {
            return value;
        }

        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String normalizeMainClassName(String mainClassName) {
        String normalized = trimToNull(mainClassName);
        if (normalized == null) {
            return null;
        }
        return normalized.replace('/', '.');
    }

    private String normalizeOwner(String ownerName) {
        String normalized = trimToNull(ownerName);
        if (normalized == null) {
            throw new GradleException("EndCrystalWM injector: injectorOwnerInternalName must not be blank");
        }
        return normalized.replace('.', '/');
    }

    private String normalizeLanguageTag(String languageTag) {
        String normalized = trimToNull(languageTag);
        if (normalized == null) {
            return "en";
        }
        return normalized.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } finally {
            inputStream.close();
        }
    }

    private static final class JarSnapshot {
        private final List<JarEntryData> entries;
        private final Map<String, JarEntryData> entriesByName;

        private JarSnapshot(List<JarEntryData> entries, Map<String, JarEntryData> entriesByName) {
            this.entries = entries;
            this.entriesByName = entriesByName;
        }
    }

    private static final class JarEntryData {
        private final String name;
        private final long time;
        private final boolean directory;
        private byte[] bytes;

        private JarEntryData(String name, byte[] bytes, long time, boolean directory) {
            this.name = name;
            this.bytes = bytes;
            this.time = time;
            this.directory = directory;
        }
    }

    private static final class InjectionBlock {
        private final InsnList instructions;
        private final List<TryCatchBlockNode> tryCatchBlocks;

        private InjectionBlock(InsnList instructions, List<TryCatchBlockNode> tryCatchBlocks) {
            this.instructions = instructions;
            this.tryCatchBlocks = tryCatchBlocks;
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader classLoader;

        private SafeClassWriter(ClassReader classReader, int writerFlags, ClassLoader classLoader) {
            super(classReader, writerFlags);
            this.classLoader = classLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> first = loadClass(type1);
                Class<?> second = loadClass(type2);
                if (first.isAssignableFrom(second)) {
                    return type1;
                }
                if (second.isAssignableFrom(first)) {
                    return type2;
                }
                if (first.isInterface() || second.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    first = first.getSuperclass();
                } while (first != null && !first.isAssignableFrom(second));
                return first == null ? "java/lang/Object" : first.getName().replace('.', '/');
            } catch (Throwable ignored) {
                return "java/lang/Object";
            }
        }

        private Class<?> loadClass(String internalName) throws ClassNotFoundException {
            String className = internalName.replace('/', '.');
            try {
                return Class.forName(className, false, classLoader);
            } catch (ClassNotFoundException primary) {
                ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
                if (contextLoader != null && contextLoader != classLoader) {
                    return Class.forName(className, false, contextLoader);
                }
                throw primary;
            }
        }
    }
}
