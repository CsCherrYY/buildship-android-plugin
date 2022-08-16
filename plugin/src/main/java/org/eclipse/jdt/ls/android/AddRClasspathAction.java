package org.eclipse.jdt.ls.android;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.Library;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AddRClasspathAction implements Action<Classpath> {

    private final Project project;
    private final FileReferenceFactory fileReferenceFactory;

    public AddRClasspathAction(Project project) {
        this.project = project;
        this.fileReferenceFactory = new FileReferenceFactory();
    }

    @Override
    public void execute(Classpath classpath) {
        Path intermediatesPath = Paths.get("build", "intermediates");
        File intermediatesFolder = new File(project.getProjectDir(), intermediatesPath.toString());
        if (intermediatesFolder.exists() && intermediatesFolder.isDirectory()) {
            for (File intermediate : intermediatesFolder.listFiles()) {
                if (intermediate.getAbsolutePath().endsWith("r_class_jar")) {
                    Path RPath = Paths.get(intermediate.getPath(), "debug", "R.jar");
                    if (RPath.toFile().exists()) {
                        Path projectPath = project.getProjectDir().toPath();
                        Path relativePath = projectPath.relativize(RPath);
                        classpath.getEntries().add(new Library(this.fileReferenceFactory.fromPath(relativePath.toString())));
                        return;
                    } else {
                        RPath = Paths.get(intermediate.getPath(), "debug", "generateDebugRFile", "R.jar");
                        if (RPath.toFile().exists()) {
                            Path projectPath = project.getProjectDir().toPath();
                            Path relativePath = projectPath.relativize(RPath);
                            classpath.getEntries().add(new Library(this.fileReferenceFactory.fromPath(relativePath.toString())));
                            return;
                        }
                    }
                }
            }
        }
        Path RFolder = Paths.get("build", "generated", "not_namespaced_r_class_sources", "debug", "processDebugResources", "r");
        File RFolderFile = new File(project.getProjectDir(), RFolder.toString());
        if (RFolderFile.exists()) {
            classpath.getEntries().add(new SourceFolder(RFolder.toString(), JavaLanguageServerAndroidPlugin.DEFAULT_OUTPUT_MAIN));
        }
    }
}
