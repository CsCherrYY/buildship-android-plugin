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

public class AddDataBindingClasspathAction implements Action<Classpath> {
    private final Project project;
    private final FileReferenceFactory fileReferenceFactory;

    public AddDataBindingClasspathAction(Project project) {
        this.project = project;
        this.fileReferenceFactory = new FileReferenceFactory();
    }

    @Override
    public void execute(Classpath classpath) {
        Path relativePath = Paths.get("build", "generated", "data_binding_base_class_source_out", "debug", "out");
        File dataBindingFolder = new File(project.getProjectDir(), relativePath.toString());
        if (dataBindingFolder.exists()) {
            classpath.getEntries().add(new SourceFolder(relativePath.toString(), JavaLanguageServerAndroidPlugin.DEFAULT_OUTPUT_MAIN));
        }
    }
}
