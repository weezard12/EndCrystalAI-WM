package me.weezard12.endCrystalWM.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

public final class EndCrystalWmInjectorPlugin implements Plugin<Project> {
    private static final String EXTENSION_NAME = "endCrystalWmInjector";
    private static final String DEFAULT_INJECTOR_OWNER = "me/weezard12/endCrystalWM/EndCrystalWM";

    @Override
    public void apply(Project project) {
        EndCrystalWmInjectorExtension extension =
                project.getExtensions().create(EXTENSION_NAME, EndCrystalWmInjectorExtension.class);

        extension.getEnabled().convention(true);
        extension.getInjectorOwnerInternalName().convention(DEFAULT_INJECTOR_OWNER);
        extension.getFailOnMissingPluginYml().convention(true);
        extension.getFailOnMissingMainClass().convention(true);

        project.getPluginManager().withPlugin("java", ignored -> wireArchiveTask(project, extension, "jar"));
        project.getPluginManager().withPlugin("java-library", ignored -> wireArchiveTask(project, extension, "jar"));
        project.getPluginManager().withPlugin("com.github.johnrengelman.shadow", ignored -> wireArchiveTask(project, extension, "shadowJar"));
        project.getPluginManager().withPlugin("io.github.goooler.shadow", ignored -> wireArchiveTask(project, extension, "shadowJar"));
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
                return Boolean.TRUE.equals(extension.getEnabled().getOrElse(Boolean.TRUE))
                        && typedTask.getTargetJar().isPresent();
            });
        });
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
}
