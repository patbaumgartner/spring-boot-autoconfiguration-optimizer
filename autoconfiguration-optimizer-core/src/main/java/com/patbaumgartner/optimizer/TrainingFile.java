package com.patbaumgartner.optimizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * The on-disk contract for the training file shared by the training run that writes it,
 * the import filter that reads it, and the build plugins that verify it.
 *
 * <p>
 * The file records the auto-configurations that were <em>not</em> loaded during training,
 * rather than those that were. Recording exclusions means a candidate the training run
 * never saw - typically one introduced by a dependency added after training - is simply
 * unknown to the filter and is therefore allowed. Recording the loaded set instead made
 * every such candidate silently disappear from the application.
 *
 * <p>
 * <strong>This does not make a stale training file universally safe.</strong> Exclusions
 * are decided by the conditions evaluated during training, and those conditions can start
 * matching without any candidate name changing - for example when removing a dependency
 * makes a {@code @ConditionalOnMissingClass} auto-configuration applicable. Re-run
 * training whenever dependencies or the runtime configuration change; the recorded
 * candidate digest lets the build plugins detect the classpath half of that drift.
 */
public final class TrainingFile {

	/**
	 * Classpath location the build plugins place the training file at, and the location
	 * {@link OptimizedAutoConfigurationImportFilter} reads at startup.
	 */
	public static final String RESOURCE_LOCATION = "META-INF/autoconfiguration-optimizer.properties";

	/**
	 * Version of the file layout. The filter refuses to apply a file it does not
	 * recognise, so an older or newer optimizer degrades to running unoptimized instead
	 * of misreading the recorded set.
	 */
	public static final int FORMAT_VERSION = 2;

	public static final String FORMAT_VERSION_KEY = "autoconfiguration.optimizer.format-version";

	public static final String EXCLUDED_CONFIGURATIONS_KEY = "autoconfiguration.optimizer.excluded-configurations";

	public static final String CANDIDATE_DIGEST_KEY = "autoconfiguration.optimizer.candidate-digest";

	public static final String CANDIDATE_COUNT_KEY = "autoconfiguration.optimizer.candidate-count";

	public static final String TRAINING_TIMESTAMP_KEY = "autoconfiguration.optimizer.training-timestamp";

	public static final String SPRING_BOOT_VERSION_KEY = "autoconfiguration.optimizer.spring-boot-version";

	private TrainingFile() {
	}

	/**
	 * Computes a stable digest over the set of auto-configuration candidate class names.
	 * Recorded during training and recomputed by the build plugins, it detects that the
	 * set of candidates on the classpath has changed since the training file was
	 * produced.
	 * @param candidateClassNames the auto-configuration candidate class names
	 * @return the digest as a lower-case hexadecimal SHA-256 string
	 */
	public static String candidateDigest(Collection<String> candidateClassNames) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every Java platform implementation", ex);
		}
		for (String candidate : new TreeSet<>(candidateClassNames)) {
			digest.update(candidate.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
		}
		return HexFormat.of().formatHex(digest.digest());
	}

}
