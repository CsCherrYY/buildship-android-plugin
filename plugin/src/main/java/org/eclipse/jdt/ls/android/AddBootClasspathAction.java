package org.eclipse.jdt.ls.android;

import groovy.util.Node;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.plugins.ide.eclipse.model.*;
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                        if (!((File) bootClasspath).getAbsolutePath().endsWith("android.jar")) {
                            return;
                        }
                        Library library = new Library(fileReferenceFactory.fromFile((File) bootClasspath));
                        classpath.getEntries().add(library);
                    }
                });
            }
        } catch (Exception e) {
            // Do nothing
        }
    }
}
