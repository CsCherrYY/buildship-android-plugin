import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.internal.FactoryNamedDomainObjectContainer
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.eclipse.model.SourceFolder
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.Library
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory
import org.gradle.util.GradleVersion

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile


class JavaLanguageServerAndroidPlugin implements Plugin<Project> {

    public static final String DEFAULT_OUTPUT_MAIN = "bin/main"
    public static final String DEFAULT_OUTPUT_TEST = "bin/test"

    private static final List<String> CLASSPATH_CONFIGURATIONS = Arrays.asList("debugUnitTestRuntimeClasspath",
            "debugCompileClasspath", "debugAndroidTestRuntimeClasspath")
    private static final List<String> UNRESOLVED_CLASSPATH_CONFIGURATIONS = Arrays.asList(
            "implementation"/*, "testImplementation", "androidTestImplementation",
            "compile", "testCompile", "androidTestCompile", "compileOnly",
            "api", "testApi", "androidTestApi"*/)
    private static final String ANDROID_BASE_PLUGIN_ID = "com.android.base"
    private static final List<String> ANDROID_PLUGIN_IDS = Arrays.asList("android", "android-library", "com.android.application", "com.android.library", "com.android.test")

    void apply(Project project) {
        if (!ResolveAndroidProjectAction.isAndroidProject(project)) {
            return
        }
        // buildship indicator
        if (!project.hasProperty("eclipse")) {
            return
        }

        project.afterEvaluate {
            ResolveAndroidProjectAction a = new ResolveAndroidProjectAction()
            a.execute(project)
        }

        //project.afterEvaluate(new AfterEvaluateProjectAction())
    }

    private static class AfterEvaluateProjectAction implements Action<Project> {
        @Override
        void execute(Project project) {
            EclipseModel eclipseModel = (EclipseModel) project.property("eclipse")
            // https://www.eclipse.org/community/eclipse_newsletter/2019/june/buildship.php
            if (GradleVersion.version(project.getGradle().getGradleVersion()) >= GradleVersion.version("5.4")) {
                try {
                    List<String> syncTasks = new ArrayList<>()
                    for (String taskName : project.getTasks().names) {
                        if (taskName.startsWith("compile") && taskName.endsWith("Sources")) {
                            syncTasks.add(taskName)
                        }
                    }
                    eclipseModel.synchronizationTasks(syncTasks)
                } catch (UnknownTaskException ignored) {
                    // Do nothing
                }
            }
        }
    }

    private static class ResolveAndroidProjectAction implements Action<Project> {
        @Override
        void execute(Project project) {
            if (!isAndroidProject(project)) {
                return
            }
            // buildship indicator
            if (!project.hasProperty("eclipse")) {
                return
            }
            EclipseModel eclipseModel = (EclipseModel) project.getExtensions().getByType(EclipseModel)
            addPlusConfiguration(project, eclipseModel)
            eclipseModel.getClasspath().setDownloadSources(true)
            // remove JDK container since android project should use embedded JDK types included in android.jar
            eclipseModel.classpath.containers.removeIf(container -> container.contains("JavaSE"))
            eclipseModel.classpath.file.whenMerged(new AddSourceFoldersAction(project))
            eclipseModel.classpath.file.whenMerged(new AddDataBindingClasspathAction(project))
            eclipseModel.classpath.file.whenMerged(new AddRClasspathAction(project))
            eclipseModel.classpath.file.whenMerged(new AddBuildConfigAction(project))
            // add android.jar from Android bootClasspath to project classpath
            eclipseModel.classpath.file.whenMerged(new AddAndroidSDKAction(project))
            // extract classes.jar from aar dependencies and add them to classpath
            eclipseModel.classpath.file.whenMerged(new ExtractAARDependenciesAction(project))
        }

        private static boolean isAndroidProject(Project project) {
            if (project.getPlugins().hasPlugin(ANDROID_BASE_PLUGIN_ID)) {
                return true
            }
            for (String pluginId : ANDROID_PLUGIN_IDS) {
                if (project.getPlugins().hasPlugin(pluginId)) {
                    return true
                }
            }
            return false
        }

        private static void addPlusConfiguration(Project project, EclipseModel eclipseModel) {
            List<Configuration> plusConfigurations = new ArrayList<>()
            SortedMap<String, Configuration> configurations = project.getConfigurations().getAsMap()
//            for (String config : UNRESOLVED_CLASSPATH_CONFIGURATIONS) {
//                if (configurations.containsKey(config)) {
//                    Configuration configuration = configurations.get(config)
//                    // set null target as default target
////                    for (Dependency dependency : configuration.getDependencies()) {
////                        if (dependency instanceof DefaultProjectDependency) {
////                            if (((DefaultProjectDependency) dependency).getTargetConfiguration() == null) {
////                                ((DefaultProjectDependency) dependency).setTargetConfiguration("default")
////                            }
////                        }
////                    }
//                    configuration.setCanBeResolved(true)
//                    plusConfigurations.add(configuration)
//                }
//            }
            for (String configurationName : configurations.keySet()) {
                for (String config : CLASSPATH_CONFIGURATIONS) {
                    if (configurationName.toLowerCase().contains(config.toLowerCase())) {
                        Configuration configuration = configurations.get(configurationName)
                        for (Dependency dependency : configuration.getDependencies()) {
                            if (dependency instanceof DefaultProjectDependency) {
                                if (((DefaultProjectDependency) dependency).getTargetConfiguration() == null) {
                                    ((DefaultProjectDependency) dependency).setTargetConfiguration("default")
                                }
                            }
                        }
                        plusConfigurations.add(configuration)
                    }
                }
            }
//            for (String config : CLASSPATH_CONFIGURATIONS) {
//                if (configurations.containsKey(config)) {
//                    Configuration configuration = configurations.get(config)
//                    plusConfigurations.add(configuration)
//                }
//            }
            eclipseModel.getClasspath().getPlusConfigurations().addAll(plusConfigurations)
        }
    }

    private static class AddSourceFoldersAction implements Action<Classpath> {

        private final Project project

        AddSourceFoldersAction(Project project) {
            this.project = project
        }

        @Override
        void execute(Classpath classpath) {
            Object android = project.property("android")
            try {
                Object sourceSets = android.getClass().getMethod("getSourceSets").invoke(android)
                if (sourceSets instanceof FactoryNamedDomainObjectContainer) {
                    addSourceFolder(classpath, (FactoryNamedDomainObjectContainer) sourceSets, "main", DEFAULT_OUTPUT_MAIN)
                    addSourceFolder(classpath, (FactoryNamedDomainObjectContainer) sourceSets, "test", DEFAULT_OUTPUT_TEST)
                    addSourceFolder(classpath, (FactoryNamedDomainObjectContainer) sourceSets, "androidTest", DEFAULT_OUTPUT_TEST)
                }
            } catch (Exception ignored) {
                // Do nothing
            }
        }

        private void addSourceFolder(Classpath classpath, FactoryNamedDomainObjectContainer sourceSets, String sourceSetName, String outputPath) {
            Object mainSourceSet = sourceSets.getByName(sourceSetName)
            Object javaDirectories = null
            try {
                javaDirectories = mainSourceSet.getClass().getMethod("getJavaDirectories").invoke(mainSourceSet)
            } catch (Exception ignored) {
                // Do nothing
            }
            if (javaDirectories instanceof Set) {
                for (Object javaDirectory : ((Set) javaDirectories)) {
                    if (javaDirectory instanceof File) {
                        File JavaSourceDirectory = ((File) javaDirectory)
                        if (!JavaSourceDirectory.exists()) {
                            return
                        }
                        URI JavaSourceDirectoryURI = JavaSourceDirectory.toURI()
                        File projectDirectory = project.getProjectDir()
                        URI projectDirectoryURI = projectDirectory.toURI()
                        URI relativeURI = projectDirectoryURI.relativize(JavaSourceDirectoryURI)
                        SourceFolder f = new SourceFolder(relativeURI.getPath(), outputPath)
                        if (outputPath == DEFAULT_OUTPUT_TEST) {
                            f.getEntryAttributes().put("test", "true")
                        }
                        classpath.getEntries().add(f)
                    }
                }
            }
        }
    }

    private static class AddDataBindingClasspathAction implements Action<Classpath> {
        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        AddDataBindingClasspathAction(Project project) {
            this.project = project
            this.fileReferenceFactory = new FileReferenceFactory()
        }

        @Override
        void execute(Classpath classpath) {
            Path dataBindingPath = Paths.get(project.buildDir.absolutePath, "generated", "data_binding_base_class_source_out", "debug", "out")
            if (dataBindingPath.toFile().exists()) {
                classpath.getEntries().add(new SourceFolder(project.getProjectDir().toURI().relativize(dataBindingPath.toUri()).toString(), DEFAULT_OUTPUT_MAIN))
            }
        }
    }

    private static class AddRClasspathAction implements Action<Classpath> {

        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        AddRClasspathAction(Project project) {
            this.project = project
            this.fileReferenceFactory = new FileReferenceFactory()
        }

        @Override
        void execute(Classpath classpath) {
            // from AGP 3.6 Android R class files are generated directly.
            // See: https://android-developers.googleblog.com/2020/02/android-studio-36.html
            Path intermediatesAbsolutePath = Paths.get(project.buildDir.absolutePath, "intermediates")
            File intermediatesAbsoluteFolder = intermediatesAbsolutePath.toFile()
            if (intermediatesAbsoluteFolder.exists() && intermediatesAbsoluteFolder.isDirectory()) {
                for (File intermediate : intermediatesAbsoluteFolder.listFiles()) {
                    if (intermediate.getAbsolutePath().endsWith("r_class_jar")) {
                        Path RPath = Paths.get(intermediate.getPath(), "debug", "R.jar")
                        if (RPath.toFile().exists()) {
                            Path projectPath = project.getProjectDir().toPath()
                            Path relativePath = projectPath.relativize(RPath)
                            classpath.getEntries().add(new Library(this.fileReferenceFactory.fromPath(relativePath.toString())))
                            return
                        } else {
                            RPath = Paths.get(intermediate.getPath(), "debug", "generateDebugRFile", "R.jar")
                            if (RPath.toFile().exists()) {
                                Path projectPath = project.getProjectDir().toPath()
                                Path relativePath = projectPath.relativize(RPath)
                                classpath.getEntries().add(new Library(this.fileReferenceFactory.fromPath(relativePath.toString())))
                                return
                            }
                        }
                    }
                }
            }
            // TODO: support variable R folders
            Path RAbsoluteFolderPath = Paths.get(project.buildDir.absolutePath, "generated", "not_namespaced_r_class_sources", "debug", "processDebugResources", "r")
            if (RAbsoluteFolderPath.toFile().exists()) {
                classpath.getEntries().add(new SourceFolder(project.getProjectDir().toURI().relativize(RAbsoluteFolderPath.toUri()).toString(), DEFAULT_OUTPUT_MAIN))
            } else {
                RAbsoluteFolderPath = Paths.get(project.buildDir.absolutePath, "generated", "source", "r", "debug")
                if (RAbsoluteFolderPath.toFile().exists()) {
                    classpath.getEntries().add(new SourceFolder(project.getProjectDir().toURI().relativize(RAbsoluteFolderPath.toUri()).toString(), DEFAULT_OUTPUT_MAIN))
                }
            }
        }
    }

    private static class AddBuildConfigAction implements Action<Classpath> {
        private final Project project

        AddBuildConfigAction(Project project) {
            this.project = project
        }

        @Override
        void execute(Classpath classpath) {
            Path buildConfigPath = Paths.get(project.buildDir.absolutePath, "generated", "source", "buildConfig", "debug")
            if (buildConfigPath.toFile().exists()) {
                classpath.getEntries().add(new SourceFolder(project.getProjectDir().toURI().relativize(buildConfigPath.toUri()).toString(), DEFAULT_OUTPUT_MAIN))
            }
        }
    }

    private static class AddAndroidSDKAction implements Action<Classpath> {
        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        AddAndroidSDKAction(Project project) {
            this.project = project
            this.fileReferenceFactory = new FileReferenceFactory()
        }

        @Override
        void execute(Classpath classpath) {
            Object android = project.property("android")
            try {
                Object bootClasspathList = android.getClass().getMethod("getBootClasspath").invoke(android)
                if (bootClasspathList instanceof List) {
                    for (Object bootClasspath : ((List) bootClasspathList)) {
                        if (bootClasspath instanceof File) {
                            File file = (File) bootClasspath
                            if (file.getAbsolutePath().endsWith("android.jar")) {
                                classpath.getEntries().add(new Library(fileReferenceFactory.fromFile(file)))
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Do nothing
            }
        }
    }

    private static class ExtractAARDependenciesAction implements Action<Classpath> {

        private static final String EXTRACT_AAR_RELATIVE_PATH = "aarLibraries"
        private static final String AAR_EXTENSION = ".aar"
        private static final String DEPENDENCY_ENTRY_NAME = "classes.jar"

        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        ExtractAARDependenciesAction(Project project) {
            this.project = project
            this.fileReferenceFactory = new FileReferenceFactory()
        }

        @Override
        void execute(Classpath classpath) {
            List<ClasspathEntry> jarEntries = new ArrayList<>()
            for (ClasspathEntry entry : classpath.entries) {
                if (entry instanceof Library && ((Library) entry).path.endsWith(AAR_EXTENSION)) {
                    ClasspathEntry aarClasspathEntry = getClasspathEntryFromAAR((Library) entry)
                    if (aarClasspathEntry != null) {
                        jarEntries.add(aarClasspathEntry)
                    }
                } else {
                    jarEntries.add(entry)
                }
            }
            classpath.setEntries(jarEntries)
        }

        private ClasspathEntry getClasspathEntryFromAAR(Library aarLibrary) {
            String libraryName = aarLibrary.getModuleVersion().toString().replaceAll(":", "-")
            Path libraryFolderPath = Paths.get(project.buildDir.absolutePath, EXTRACT_AAR_RELATIVE_PATH)
            File libraryFolder = libraryFolderPath.toFile()
            try {
                if (!libraryFolder.exists()) {
                    if (!libraryFolder.mkdirs()) {
                        return null
                    }
                }
            } catch (SecurityException ignored) {
                return null
            }
            Path libraryPath = libraryFolderPath.resolve(Paths.get(libraryName + ".jar"))
            File libraryFile = libraryPath.toFile()
            if (!libraryFile.exists()) {
                try (ZipFile zipFile = new ZipFile(new File(aarLibrary.getPath()))) {
                    for (ZipEntry entry : zipFile.entries()) {
                        if (entry.name == DEPENDENCY_ENTRY_NAME) {
                            InputStream ins = zipFile.getInputStream(entry)
                            Files.copy(ins, libraryPath)
                            break
                        }
                    }
                } catch (IOException | NullPointerException ignored) {
                    return null
                }
            }
            if (libraryFile.exists()) {
                Library library = new Library(fileReferenceFactory.fromFile(libraryFile))
                library.setSourcePath(aarLibrary.getSourcePath())
                return library
            }
            return null
        }
    }
}

