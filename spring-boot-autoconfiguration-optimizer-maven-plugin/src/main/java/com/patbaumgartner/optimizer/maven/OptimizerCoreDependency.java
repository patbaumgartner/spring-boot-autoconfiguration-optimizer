package com.patbaumgartner.optimizer.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * Checks that the optimizer core is a runtime dependency of the project being built.
 *
 * <p>
 * Without it the packaged application has no import filter, so a training file is read by
 * nobody: the build succeeds, the application starts, and nothing is optimized. Both
 * goals that imply the optimizer is in use verify this rather than let it pass silently.
 */
final class OptimizerCoreDependency {

	static final String GROUP_ID = "com.patbaumgartner";

	static final String ARTIFACT_ID = "autoconfiguration-optimizer-core";

	private OptimizerCoreDependency() {
	}

	/**
	 * Returns the declared core artifact, or {@code null} when the project does not have
	 * it on its runtime classpath.
	 * @param project the project being built
	 * @return the core artifact or {@code null}
	 */
	static Artifact find(MavenProject project) {
		return project.getArtifacts()
			.stream()
			.filter((artifact) -> GROUP_ID.equals(artifact.getGroupId())
					&& ARTIFACT_ID.equals(artifact.getArtifactId()))
			.filter((artifact) -> !Artifact.SCOPE_TEST.equals(artifact.getScope())
					&& !Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Fails with the dependency declaration to add when the core is absent.
	 * @param project the project being built
	 * @param version the version to suggest, normally the plugin's own
	 * @return the declared core artifact
	 * @throws MojoFailureException if the core is not a runtime dependency
	 */
	static Artifact require(MavenProject project, String version) throws MojoFailureException {
		Artifact core = find(project);
		if (core == null) {
			throw new MojoFailureException("Spring Boot Autoconfiguration Optimizer: " + ARTIFACT_ID
					+ " is not on the project's runtime classpath. The optimizer needs it at runtime to filter "
					+ "auto-configurations, so add it to your pom.xml:\n\n" + "        <dependency>\n"
					+ "            <groupId>" + GROUP_ID + "</groupId>\n" + "            <artifactId>" + ARTIFACT_ID
					+ "</artifactId>\n" + "            <version>" + version + "</version>\n"
					+ "            <scope>runtime</scope>\n" + "        </dependency>\n");
		}
		return core;
	}

}
