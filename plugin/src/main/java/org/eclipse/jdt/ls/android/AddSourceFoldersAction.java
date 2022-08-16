package org.eclipse.jdt.ls.android;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.internal.FactoryNamedDomainObjectContainer;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.Library;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Set;

public class AddSourceFoldersAction implements Action<Classpath> {

    private final Project project;

    public AddSourceFoldersAction(Project project) {
        this.project = project;
    }

    @Override
    public void execute(Classpath classpath) {
        Object android = project.property("android");
        try {
            Object sourceSets = android.getClass().getMethod("getSourceSets").invoke(android);
            if (sourceSets instanceof FactoryNamedDomainObjectContainer) {
                addSourceFolder(classpath, (FactoryNamedDomainObjectContainer) sourceSets, "main", JavaLanguageServerAndroidPlugin.DEFAULT_OUTPUT_MAIN);
                addSourceFolder(classpath, (FactoryNamedDomainObjectContainer) sourceSets, "test", JavaLanguageServerAndroidPlugin.DEFAULT_OUTPUT_TEST);
                addSourceFolder(classpath, (FactoryNamedDomainObjectContainer) sourceSets, "androidTest", JavaLanguageServerAndroidPlugin.DEFAULT_OUTPUT_TEST);
            }
        } catch (Exception e) {
            // Do nothing
        }
    }

    private void addSourceFolder(Classpath classpath, FactoryNamedDomainObjectContainer sourceSets, String sourceSetName, String outputPath) {
        Object mainSourceSet = sourceSets.getByName(sourceSetName);
        Object javaDirectories = null;
        try {
            javaDirectories = mainSourceSet.getClass().getMethod("getJavaDirectories").invoke(mainSourceSet);
        } catch (Exception e) {
            // Can't find getJavaDirectories method
        }
        if (javaDirectories instanceof Set) {
            ((Set) javaDirectories).forEach(javaDirectory -> {
                if (javaDirectory instanceof File) {
                    File JavaSourceDirectory = ((File) javaDirectory);
                    if (!JavaSourceDirectory.exists()) {
                        return;
                    }
                    URI JavaSourceDirectoryURI = JavaSourceDirectory.toURI();
                    File projectDirectory = project.getProjectDir();
                    URI projectDirectoryURI = projectDirectory.toURI();
                    URI relativeURI = projectDirectoryURI.relativize(JavaSourceDirectoryURI);
                    SourceFolder f = new SourceFolder(relativeURI.getPath(), outputPath);
                    if (outputPath.equals(JavaLanguageServerAndroidPlugin.DEFAULT_OUTPUT_TEST)) {
                        f.getEntryAttributes().put("test", "true");
                    }
                    classpath.getEntries().add(f);
                }
            });
        }
    }
}
