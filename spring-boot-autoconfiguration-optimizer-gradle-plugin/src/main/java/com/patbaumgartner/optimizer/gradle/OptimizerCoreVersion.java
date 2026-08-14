package com.patbaumgartner.optimizer.gradle;

import org.gradle.api.GradleException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the optimizer version this plugin was built with, so the core dependency it adds
 * to a project always matches the plugin. The value is written into the plugin JAR by the
 * build.
 */
final class OptimizerCoreVersion {

    private static final String RESOURCE = "optimizer.properties";

    private static final String VERSION_KEY = "core.version";

    private OptimizerCoreVersion() {
    }

    static String get() {
        try (InputStream in = OptimizerCoreVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new GradleException(
                        "The Spring Boot Autoconfiguration Optimizer plugin JAR is missing " + RESOURCE
                                + "; it cannot determine which autoconfiguration-optimizer-core version to use.");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty(VERSION_KEY);
            if (version == null || version.isBlank()) {
                throw new GradleException(RESOURCE + " does not declare " + VERSION_KEY + ".");
            }
            return version;
        }
        catch (IOException ex) {
            throw new GradleException("Failed to read " + RESOURCE + " from the plugin JAR", ex);
        }
    }

}
