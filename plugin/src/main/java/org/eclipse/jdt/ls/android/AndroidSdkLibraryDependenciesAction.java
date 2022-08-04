package org.eclipse.jdt.ls.android;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.plugins.ide.eclipse.model.*;
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory;

public class AndroidSdkLibraryDependenciesAction implements Action<Classpath> {
    private final Project project;

    public AndroidSdkLibraryDependenciesAction(Project project) {
        this.project = project;
    }

    @Override
    public void execute(Classpath classpath) {
        classpath.getEntries().add(androidSdkEntry());
    }

    private ClasspathEntry androidSdkEntry() {
        FileReferenceFactory fileReferenceFactory = new FileReferenceFactory();
        // TODO: get SDK version
        // TODO: use variable
        Library library = new Library(fileReferenceFactory.fromPath("C:/Users/chenshi/AppData/Local/Android/Sdk/platforms/android-28/android.jar"));
        return library;
    }
}
