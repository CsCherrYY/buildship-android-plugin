import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.internal.DefaultDomainObjectCollection
import org.gradle.api.internal.DefaultNamedDomainObjectCollection
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.api.tasks.compile.JavaCompile
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
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile


class JavaLanguageServerAndroidPlugin implements Plugin<Project> {

    private static final String ANDROID_PROPERTY = "android"
    // buildship indicator
    private static final String ECLIPSE_PROPERTY = "eclipse"

    private static final String DEFAULT_OUTPUT_MAIN = "bin/main"
    private static final String DEFAULT_OUTPUT_TEST = "bin/test"
    private static final String DEFAULT_OUTPUT_ANDROID_TEST = "bin/androidTest"
    private static final List<String> ANDROID_PLUGIN_IDS = Arrays.asList("com.android.application", "com.android.library")
    private static final String ANDROID_KOTLIN_PLUGIN_ID = "kotlin-android"
    private static final List<String> SUPPORTED_SOURCE_SET_NAMES = Arrays.asList("main", "test", "androidTest")
    private static final List<String> SUPPORTED_CONFIGURATION_KEYS = Arrays.asList("implementationConfigurationName", "apiConfigurationName", "compileConfigurationName", "compileOnlyConfigurationName", "runtimeOnlyConfigurationName")

    private static final String OPTIONAL_ATTRIBUTE = "optional"
    private static final String TEST_ATTRIBUTE = "optional"

    void apply(Project project) {
        if (!isSupportedAndroidProject(project)) {
            return
        }
        if (!project.hasProperty(ECLIPSE_PROPERTY)) {
            return
        }
        project.afterEvaluate {
            EclipseModel eclipseModel = (EclipseModel) project.property(ECLIPSE_PROPERTY)
            // eclipseModel.synchronizationTasks() is available since Gradle 5.4
            // see: https://www.eclipse.org/community/eclipse_newsletter/2019/june/buildship.php
            if (GradleVersion.version(project.getGradle().getGradleVersion()) >= GradleVersion.version("5.4")) {
                List<Object> synchronizationTasks = new ArrayList<>()
                for (Object variant : getAndroidDebuggableVariants(project)) {
                    try {
                        synchronizationTasks.add(variant.properties.get("variantData").properties.get("taskContainer").properties.get("compileTask"))
                    } catch (NullPointerException ignored) {
                        // NPE occurs when the variant doesn't have related properties, we just skip these unsupported scenarios
                    }
                }
                eclipseModel.synchronizationTasks(synchronizationTasks)
            }
            // Get project configurations from supported sourceSets names and configuration keys,
            // and add them to eclipseModel.classpath.plusConfigurations
            addPlusConfiguration(project, eclipseModel)
            eclipseModel.classpath.setDownloadSources(true)
            // Remove JDK container since android project should use embedded JDK types included in android.jar
            eclipseModel.classpath.containers.removeIf(container -> container.contains("JavaSE"))
            // Add supported source sets to source folders of eclipse model
            eclipseModel.classpath.file.whenMerged(new AddSourceFoldersAction(project))
            // Add data binding files to source folders of eclipse model
            eclipseModel.classpath.file.whenMerged(new AddDataBindingFilesAction(project))
            // Add R files to source folders of eclipse model
            eclipseModel.classpath.file.whenMerged(new AddRFilesAction(project))
            // Add buildconfig files to source folders of eclipse model
            eclipseModel.classpath.file.whenMerged(new AddBuildConfigFilesAction(project))
            // Add android.jar to project classpath of eclipse model
            eclipseModel.classpath.file.whenMerged(new AddAndroidSDKAction(project))
            // Add project dependencies to project classpath of eclipse model
            // for aar dependencies, extract classes.jar and add them to project classpath
            eclipseModel.classpath.file.whenMerged(new AddProjectDependenciesAction(project))
        }
    }

    private static boolean isSupportedAndroidProject(Project project) {
        // kotlin compile task can't be executed via eclipseModel.synchronizationTasks()
        if (project.plugins.hasPlugin(ANDROID_KOTLIN_PLUGIN_ID)) {
            return false
        }
        for (String pluginId : ANDROID_PLUGIN_IDS) {
            if (project.plugins.hasPlugin(pluginId)) {
                return true
            }
        }
        return false
    }

    private static void addPlusConfiguration(Project project, EclipseModel eclipseModel) {
        List<String> plusConfigurationNames = new ArrayList<>()
        for (String name : SUPPORTED_SOURCE_SET_NAMES) {
            Object sourceSet = getAndroidSourceSetByName(project, name)
            if (sourceSet == null) {
                continue
            }
            for (String key : SUPPORTED_CONFIGURATION_KEYS) {
                if (sourceSet.properties.containsKey(key)) {
                    plusConfigurationNames.add(sourceSet.properties.get(key).toString())
                }
            }
        }
        SortedMap<String, Configuration> configurations = project.getConfigurations().getAsMap()
        for (String config : plusConfigurationNames) {
            if (configurations.containsKey(config)) {
                Configuration configuration = configurations.get(config)
                // set null target as default target
                for (Dependency dependency : configuration.getDependencies()) {
                    if (dependency instanceof DefaultProjectDependency) {
                        if (((DefaultProjectDependency) dependency).getTargetConfiguration() == null) {
                            ((DefaultProjectDependency) dependency).setTargetConfiguration("default")
                        }
                    }
                }
                configuration.setCanBeResolved(true)
                eclipseModel.classpath.plusConfigurations.add(configuration)
            }
        }
    }

    private static Object getAndroidSourceSetByName(Project project, String name) {
        Object android = project.property(ANDROID_PROPERTY)
        if (android.properties.containsKey("sourceSets")) {
            Object sourceSets = android.properties.get("sourceSets")
            if (sourceSets instanceof DefaultNamedDomainObjectCollection) {
                DefaultNamedDomainObjectCollection sourceCollection = ((DefaultNamedDomainObjectCollection) sourceSets)
                if (sourceCollection.names.contains(name)) {
                    return sourceCollection.getByName(name)
                }
            }
        }
        return null
    }

    private static List<Object> getAndroidDebuggableVariants(Project project) {
        Object android = project.property(ANDROID_PROPERTY)
        Object variants = null
        if (project.plugins.hasPlugin("com.android.application")) {
            // For android application, variants come from applicationVariants property
            if (android.properties.containsKey("applicationVariants")) {
                variants = android.properties.get("applicationVariants")
            }
        } else if (project.plugins.hasPlugin("com.android.library")) {
            // For android library, variants come from libraryVariants property
            if (android.properties.containsKey("libraryVariants")) {
                variants = android.properties.get("libraryVariants")
            }
        }
        if (variants instanceof DefaultDomainObjectCollection) {
            return ((DefaultDomainObjectCollection) variants).stream().filter(variant -> {
                try {
                    Object debuggable = variant.properties.get("buildType").properties.get("debuggable")
                    return debuggable instanceof Boolean && ((Boolean) debuggable)
                } catch (NullPointerException ignored) {
                    return false
                }
            }).collect(Collectors.toList())
        }
        return Collections.emptyList()
    }

    private static void addFolderToSourceFolder(Classpath classpath, Project project, File folder, String outputPath) {
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                return
            }
        }
        if (!folder.isDirectory()) {
            return
        }
        SourceFolder sourceFolder = new SourceFolder(project.getProjectDir().toURI().relativize(folder.toURI()).toString(), outputPath)
        sourceFolder.entryAttributes.put(OPTIONAL_ATTRIBUTE, "true")
        if (outputPath == DEFAULT_OUTPUT_TEST || outputPath == DEFAULT_OUTPUT_ANDROID_TEST) {
            sourceFolder.getEntryAttributes().put(TEST_ATTRIBUTE, "true")
        }
        classpath.entries.add(sourceFolder)
    }

    private static class AddSourceFoldersAction implements Action<Classpath> {
        // <sourceSetName, outputPath>
        private static final Map<String, String> SUPPORTED_SOURCE_SETS = new HashMap<>()
        private final Project project

        static {
            SUPPORTED_SOURCE_SETS.put("main", DEFAULT_OUTPUT_MAIN)
            SUPPORTED_SOURCE_SETS.put("test", DEFAULT_OUTPUT_TEST)
            SUPPORTED_SOURCE_SETS.put("androidTest", DEFAULT_OUTPUT_ANDROID_TEST)
        }

        AddSourceFoldersAction(Project project) {
            this.project = project
        }

        @Override
        void execute(Classpath classpath) {
            for (String key : SUPPORTED_SOURCE_SETS.keySet()) {
                Object sourceSet = getAndroidSourceSetByName(project, key)
                if (sourceSet == null) {
                    continue
                }
                if (sourceSet.properties.containsKey("javaDirectories")) {
                    Object javaDirectories = sourceSet.properties.get("javaDirectories")
                    if (javaDirectories instanceof Collection) {
                        for (Object javaDirectory : (Collection) javaDirectories) {
                            if (javaDirectory instanceof File) {
                                addFolderToSourceFolder(classpath, project, (File) javaDirectory, SUPPORTED_SOURCE_SETS.get(key))
                            }
                        }
                    }
                }
            }
        }
    }

    private static class AddDataBindingFilesAction implements Action<Classpath> {
        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        AddDataBindingFilesAction(Project project) {
            this.project = project
            this.fileReferenceFactory = new FileReferenceFactory()
        }

        @Override
        void execute(Classpath classpath) {
            for (Object variant : getAndroidDebuggableVariants(project)) {
                try {
                    // TODO: get dataBinding task from variants?
                    String variantName = variant.properties.get("name")
                    String taskName = "dataBindingGenBaseClasses" + variantName.substring(0, 1).toUpperCase() + variantName.substring(1)
                    Task dataBindingGenTask = project.tasks.getByName(taskName)
                    Object outFolder = dataBindingGenTask.properties.get("sourceOutFolder").properties.get("orNull").properties.get("asFile")
                    if (outFolder instanceof File) {
                        addFolderToSourceFolder(classpath, project, (File) outFolder, DEFAULT_OUTPUT_MAIN)
                    }
                } catch (NullPointerException | UnknownTaskException ignored) {
                    // NPE occurs when the variant doesn't have related properties, we just skip these unsupported scenarios
                }
            }
        }
    }

    private static class AddRFilesAction implements Action<Classpath> {
        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        AddRFilesAction(Project project) {
            this.project = project
            this.fileReferenceFactory = new FileReferenceFactory()
        }

        @Override
        void execute(Classpath classpath) {
            for (Object variant : getAndroidDebuggableVariants(project)) {
                try {
                    Object outputs = variant.properties.get("outputs")
                    if (outputs instanceof DefaultNamedDomainObjectCollection) {
                        for (Object output : outputs) {
                            // output.processResources.sourceOutputDir || output.processResources.RClassOutputJar
                            Object processResourcesTask = output.properties.get("processResources")
                            if (processResourcesTask.properties.containsKey("sourceOutputDir")) {
                                Object outputDir = processResourcesTask.properties.get("sourceOutputDir")
                                if (outputDir instanceof File) {
                                    addRFileToClasspath(classpath, (File) outputDir)
                                    continue
                                }
                            }
                            if (processResourcesTask.properties.containsKey("RClassOutputJar")) {
                                Object outputJar = processResourcesTask.properties.get("RClassOutputJar").properties.get("orNull").properties.get("asFile")
                                if (outputJar instanceof File) {
                                    addRFileToClasspath(classpath, (File) outputJar)
                                }
                            }
                        }
                    }
                } catch (NullPointerException ignored) {
                    // NPE occurs when the variant doesn't have related properties, we just skip these unsupported scenarios
                }
            }
        }

        private void addRFileToClasspath(Classpath classpath, File RFile) {
            if (RFile.name.endsWith(".jar")) {
                Library library = new Library(this.fileReferenceFactory.fromPath(project.getProjectDir().toPath().relativize(RFile.toPath()).toString()))
                library.entryAttributes.put(OPTIONAL_ATTRIBUTE, "true")
                classpath.getEntries().add(library)
            } else {
                addFolderToSourceFolder(classpath, this.project, RFile, DEFAULT_OUTPUT_MAIN)
            }
        }
    }

    private static class AddBuildConfigFilesAction implements Action<Classpath> {
        private final Project project

        AddBuildConfigFilesAction(Project project) {
            this.project = project
        }

        @Override
        void execute(Classpath classpath) {
            for (Object variant : getAndroidDebuggableVariants(project)) {
                try {
                    // for old AGP, the file instance can be directly got via variant.generateBuildConfig.sourceOutputDir
                    Object outputDir = variant.properties.get("generateBuildConfig").properties.get("sourceOutputDir")
                    if (outputDir instanceof File) {
                        addFolderToSourceFolder(classpath, this.project, (File) outputDir, DEFAULT_OUTPUT_MAIN)
                    } else {
                        // for newer AGP, the file instance can be got via variant.generateBuildConfig.sourceOutputDir.orNull.asFile
                        Object outputDirFile = outputDir.properties.get("orNull").properties.get("asFile")
                        if (outputDirFile instanceof File) {
                            addFolderToSourceFolder(classpath, this.project, (File) outputDirFile, DEFAULT_OUTPUT_MAIN)
                        }
                    }
                } catch (NullPointerException ignored) {
                    // NPE occurs when the variant doesn't have related properties, we just skip these unsupported scenarios
                }
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
            Object android = project.property(ANDROID_PROPERTY)
            try {
                Object bootClasspathList = android.properties.get("bootClasspath")
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
            } catch (NullPointerException ignored) {
                // NPE occurs when the variant doesn't have related properties, we just skip these unsupported scenarios
            }
        }
    }

    private static class AddProjectDependenciesAction implements Action<Classpath> {
        private static final String EXTRACT_AAR_RELATIVE_PATH = "aarLibraries"
        private static final String AAR_EXTENSION = ".aar"
        private static final String DEPENDENCY_ENTRY_NAME = "classes.jar"

        private final Project project
        private final FileReferenceFactory fileReferenceFactory

        AddProjectDependenciesAction(Project project) {
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
            if (!libraryFolder.exists()) {
                if (!libraryFolder.mkdirs()) {
                    return null
                }
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

