package com.nimbox.canexer.artifact;

import org.gradle.api.provider.Property;

/**
 * What the artifact declares about itself: its identity and, for a connector,
 * its plugin class. The plugin writes these into the archive manifest as the
 * {@code Nimbox-*} attributes a box reads.
 *
 * <pre>
 * canexerArtifact {
 *     kind.set(ArtifactKind.APPLICATION)
 *     provides.set("ar")
 * }
 * </pre>
 */
public abstract class ArtifactExtension {

	/** Application or connector. */
	public abstract Property<ArtifactKind> getKind();

	/** The identity ({@code Nimbox-Provides}), stable across versions. */
	public abstract Property<String> getProvides();

	/** The pf4j plugin class ({@code Nimbox-Plugin-Class}); connectors only. */
	public abstract Property<String> getPluginClass();

	/** The server API range built against ({@code Nimbox-Requires}). */
	public abstract Property<String> getRequires();

	public abstract Property<String> getVendor();

	/** The task producing the archive; defaults by kind. */
	public abstract Property<String> getArchiveTask();

}
