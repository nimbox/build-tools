package com.nimbox.canexer.artifact;

import java.util.Locale;

/**
 * What a canexer box installs: an application war, a connector jar, or the
 * server distribution itself.
 */
public enum ArtifactKind {

	APPLICATION("war"), CONNECTOR("shadowJar"), SERVER("distZip");

	private final String archiveTask;

	ArtifactKind(String archiveTask) {
		this.archiveTask = archiveTask;
	}

	/** The task that produces the artifact by default. */
	public String archiveTask() {
		return archiveTask;
	}

	/** The kind as the manager plane spells it: {@code application}, {@code connector}, {@code server}. */
	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

}
