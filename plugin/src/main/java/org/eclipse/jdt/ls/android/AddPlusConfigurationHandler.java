package org.eclipse.jdt.ls.android;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;

public class AddPlusConfigurationHandler {

    private static final List<String> CLASSPATH_CONFIGURATIONS = Arrays.asList("debugAndroidTestCompileClasspath", "debugUnitTestCompileClasspath");

    public static void addPlusConfiguration(Project project, EclipseModel eclipseModel) {
        List<Configuration> plusConfigurations = new ArrayList<>();
        ConfigurationContainer container = project.getConfigurations();
        SortedMap<String, Configuration> configurations = container.getAsMap();
        Configuration implementationConfiguration = resolveImplementationConfiguration(configurations);
        if (implementationConfiguration != null) {
            plusConfigurations.add(implementationConfiguration);
        }
        for (String configurationName : CLASSPATH_CONFIGURATIONS) {
            if (configurations.containsKey(configurationName)) {
                plusConfigurations.add(configurations.get(configurationName));
            }
        }
        eclipseModel.getClasspath().getPlusConfigurations().addAll(plusConfigurations);
    }

    private static Configuration resolveImplementationConfiguration(SortedMap<String, Configuration> configurations) {
        if (configurations.containsKey("implementation")) {
            Configuration implementationConfiguration = configurations.get("implementation");
            // set null target as default target
            implementationConfiguration.getDependencies().forEach(dependency -> {
                if (dependency instanceof DefaultProjectDependency) {
                    if (((DefaultProjectDependency) dependency).getTargetConfiguration() == null) {
                        ((DefaultProjectDependency) dependency).setTargetConfiguration("default");
                    }
                }
            });
            implementationConfiguration.setCanBeResolved(true);
            return implementationConfiguration;
        }
        return null;
    }
}
