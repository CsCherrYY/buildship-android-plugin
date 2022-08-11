package org.eclipse.jdt.ls.android;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.plugins.ide.eclipse.model.*;
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory;

import java.io.File;
import java.util.List;

public class AddBootClasspathAction implements Action<Classpath> {
    private final Project project;
    private final FileReferenceFactory fileReferenceFactory;

    public AddBootClasspathAction(Project project) {
        this.project = project;
        this.fileReferenceFactory = new FileReferenceFactory();
    }

    @Override
    public void execute(Classpath classpath) {
        Object android = project.property("android");
        try {
            Object bootClasspaths = android.getClass().getMethod("getBootClasspath").invoke(android);
            if (bootClasspaths instanceof List) {
                ((List) bootClasspaths).forEach(bootClasspath -> {
                    if (bootClasspath instanceof File) {
                        classpath.getEntries().add(new Library(fileReferenceFactory.fromFile((File) bootClasspath)));
                    }
                });
            }
        } catch (Exception e) {
            // Do nothing
        }
    }
}
