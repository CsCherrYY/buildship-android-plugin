package org.eclipse.jdt.ls.android;

import org.gradle.api.Action;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;

public class AddSourceFoldersAction implements Action<Classpath> {
    @Override
    public void execute(Classpath classpath) {
        // TODO: get source folder path from android plugin
        classpath.getEntries().add(new SourceFolder("src/main/java", "bin"));
    }
}
