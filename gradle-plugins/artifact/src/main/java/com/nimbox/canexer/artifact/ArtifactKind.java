package com.nimbox.canexer.artifact;

/**
 * What a canexer box installs: an application war or a connector jar.
 */
public enum ArtifactKind {

	APPLICATION("war"), CONNECTOR("shadowJar");

	private final String archiveTask;

	ArtifactKind(String archiveTask) {
		this.archiveTask = archiveTask;
	}

	/** The task that produces the artifact by default. */
	public String archiveTask() {
		return archiveTask;
	}

}
