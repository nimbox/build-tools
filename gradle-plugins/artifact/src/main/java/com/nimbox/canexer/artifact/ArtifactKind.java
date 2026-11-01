package com.nimbox.canexer.artifact;

import java.util.Locale;

/**
 * The four kinds of artifact, listed in this order: the bootstrap and the server
 * distributions, an application war, a connector jar.
 */
public enum ArtifactKind {

	BOOTSTRAP("distZip"), SERVER("distZip"), APPLICATION("war"), CONNECTOR("shadowJar");

	private final String archiveTask;

	ArtifactKind(String archiveTask) {
		this.archiveTask = archiveTask;
	}

	/** The task that produces the artifact by default. */
	public String archiveTask() {
		return archiveTask;
	}

	/** The kind as a manifest and the plane spell it: {@code server}, {@code application}, ... */
	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

}
