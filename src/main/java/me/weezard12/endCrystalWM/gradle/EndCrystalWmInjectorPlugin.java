package me.weezard12.endCrystalWM.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

import java.io.File;

public final class EndCrystalWmInjectorPlugin implements Plugin<Project> {
    private static final String EXTENSION_NAME = "endCrystalWmInjector";
    private static final String DEFAULT_INJECTOR_OWNER = "me/weezard12/endCrystalWM/EndCrystalWM";
    private static final String DEFAULT_RUNTIME_CONFIGURATION = "implementation";
    private static final String RUNTIME_COORDINATE_GROUP = "com.github.weezard12";
    private static final String RUNTIME_COORDINATE_ARTIFACT = "EndCrystalWM";
    private static final String AUTO_ADDED_RUNTIME_MARKER = "me.weezard12.endcrystalwm.runtimeDependencyAdded";

    @Override
    public void apply(Project project) {
        EndCrystalWmInjectorExtension extension =
                project.getExtensions().create(EXTENSION_NAME, EndCrystalWmInjectorExtension.class);

        extension.getEnabled().convention(true);
        extension.getInjectorOwnerInternalName().convention(DEFAULT_INJECTOR_OWNER);
        extension.getFailOnMissingPluginYml().convention(true);
        extension.getFailOnMissingMainClass().convention(true);
        extension.getAutoAddRuntimeDependency().convention(true);
        extension.getRuntimeConfiguration().convention(DEFAULT_RUNTIME_CONFIGURATION);
        extension.getRuntimeDependencyNotation().convention(defaultRuntimeDependencyNotation(project));

        project.getPluginManager().withPlugin("java", ignored -> wireArchiveTask(project, extension, "jar"));
        project.getPluginManager().withPlugin("java-library", ignored -> wireArchiveTask(project, extension, "jar"));
        project.getPluginManager().withPlugin("com.github.johnrengelman.shadow", ignored -> wireArchiveTask(project, extension, "shadowJar"));
        project.getPluginManager().withPlugin("io.github.goooler.shadow", ignored -> wireArchiveTask(project, extension, "shadowJar"));
        project.getPluginManager().withPlugin("com.gradleup.shadow", ignored -> wireArchiveTask(project, extension, "shadowJar"));

        project.afterEvaluate(ignored -> maybeAddRuntimeDependency(project, extension));
    }

    private void wireArchiveTask(Project project, EndCrystalWmInjectorExtension extension, String archiveTaskName) {
        String injectTaskName = "injectEndCrystalWm" + capitalize(archiveTaskName);
        TaskProvider<AbstractArchiveTask> archiveTaskProvider =
                project.getTasks().named(archiveTaskName, AbstractArchiveTask.class);
        TaskProvider<InjectEndCrystalWatermarkTask> injectTaskProvider = existingOrRegisteredInjectTask(
                project,
                extension,
                archiveTaskName,
                injectTaskName,
                archiveTaskProvider
        );
        archiveTaskProvider.configure(archiveTask -> archiveTask.finalizedBy(injectTaskProvider));
    }

    private TaskProvider<InjectEndCrystalWatermarkTask> existingOrRegisteredInjectTask(
            Project project,
            EndCrystalWmInjectorExtension extension,
            String archiveTaskName,
            String injectTaskName,
            TaskProvider<AbstractArchiveTask> archiveTaskProvider
    ) {
        Task existingTask = project.getTasks().findByName(injectTaskName);
        if (existingTask != null) {
            return project.getTasks().named(injectTaskName, InjectEndCrystalWatermarkTask.class);
        }

        return project.getTasks().register(injectTaskName, InjectEndCrystalWatermarkTask.class, injectTask -> {
            injectTask.setGroup("build");
            injectTask.setDescription("Injects EndCrystalWM bootstrap call into " + archiveTaskName + " output.");
            injectTask.dependsOn(archiveTaskProvider);
            injectTask.getTargetJar().set(archiveTaskProvider.map(archiveTask -> archiveTask.getArchiveFile().get()));
            injectTask.getInjectorOwnerInternalName().set(extension.getInjectorOwnerInternalName());
            injectTask.getMainClassOverride().set(extension.getMainClassOverride());
            injectTask.getFailOnMissingPluginYml().set(extension.getFailOnMissingPluginYml());
            injectTask.getFailOnMissingMainClass().set(extension.getFailOnMissingMainClass());
            injectTask.onlyIf(task -> {
                InjectEndCrystalWatermarkTask typedTask = (InjectEndCrystalWatermarkTask) task;
                File targetJar = typedTask.getTargetJar().getAsFile().getOrNull();
                return Boolean.TRUE.equals(extension.getEnabled().getOrElse(Boolean.TRUE))
                        && targetJar != null
                        && targetJar.isFile();
            });
        });
    }

    private void maybeAddRuntimeDependency(Project project, EndCrystalWmInjectorExtension extension) {
        if (!Boolean.TRUE.equals(extension.getEnabled().getOrElse(Boolean.TRUE))) {
            return;
        }

        if (!Boolean.TRUE.equals(extension.getAutoAddRuntimeDependency().getOrElse(Boolean.TRUE))) {
            return;
        }

        if (project.getExtensions().getExtraProperties().has(AUTO_ADDED_RUNTIME_MARKER)) {
            return;
        }

        String configurationName = trimToNull(extension.getRuntimeConfiguration().getOrElse(DEFAULT_RUNTIME_CONFIGURATION));
        if (configurationName == null) {
            project.getLogger().warn("EndCrystalWM injector: runtimeConfiguration is blank, skipping auto dependency");
            return;
        }

        if (project.getConfigurations().findByName(configurationName) == null) {
            project.getLogger().warn(
                    "EndCrystalWM injector: configuration '{}' does not exist, skipping auto dependency",
                    configurationName
            );
            return;
        }

        String notation = trimToNull(extension.getRuntimeDependencyNotation().getOrNull());
        if (notation == null) {
            project.getLogger().warn("EndCrystalWM injector: runtimeDependencyNotation is blank, skipping auto dependency");
            return;
        }

        if (!containsDependency(project, configurationName, notation)) {
            project.getDependencies().add(configurationName, notation);
            project.getLogger().lifecycle(
                    "EndCrystalWM injector: added runtime dependency '{}' to '{}'",
                    notation,
                    configurationName
            );
        }

        project.getExtensions().getExtraProperties().set(AUTO_ADDED_RUNTIME_MARKER, Boolean.TRUE);
    }

    private boolean containsDependency(Project project, String configurationName, String notation) {
        String[] segments = notation.split(":");
        if (segments.length < 2) {
            return false;
        }

        String expectedGroup = trimToNull(segments[0]);
        String expectedName = trimToNull(segments[1]);
        if (expectedName == null) {
            return false;
        }

        for (org.gradle.api.artifacts.Dependency dependency : project.getConfigurations()
                .getByName(configurationName)
                .getDependencies()) {
            if (!expectedName.equals(dependency.getName())) {
                continue;
            }

            if (expectedGroup == null || expectedGroup.equals(dependency.getGroup())) {
                return true;
            }
        }

        return false;
    }

    private String defaultRuntimeDependencyNotation(Project project) {
        String version = trimToNull(resolvePluginImplementationVersion());
        if (version == null) {
            version = "latest.release";
            project.getLogger().warn(
                    "EndCrystalWM injector: couldn't detect plugin version from manifest; defaulting runtime dependency version to '{}'",
                    version
            );
        }
        return RUNTIME_COORDINATE_GROUP + ":" + RUNTIME_COORDINATE_ARTIFACT + ":" + version;
    }

    private String resolvePluginImplementationVersion() {
        Package pluginPackage = EndCrystalWmInjectorPlugin.class.getPackage();
        if (pluginPackage == null) {
            return null;
        }
        return pluginPackage.getImplementationVersion();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() == 1) {
            return value.toUpperCase();
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
