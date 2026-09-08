package com.nimbox.canexer.artifact;

import java.nio.file.Path;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

import com.nimbox.canexer.artifact.client.BoxClientException;
import com.nimbox.canexer.artifact.client.BoxTarget;
import com.nimbox.canexer.artifact.client.ManagerClient;

/**
 * Uploads the archive to {@code POST /server/manager/install} and waits for
 * the job to settle; the build fails with the job's cause when the install
 * does. The box, its URL and the credentials are resolved by
 * {@link BoxTarget}: {@code -Pbox=<name>} names the box.
 */
public abstract class InstallToServerTask extends DefaultTask {

	@InputFile
	public abstract RegularFileProperty getArchive();

	@Input
	public abstract Property<ArtifactKind> getKind();

	@Input
	public abstract Property<String> getArtifactName();

	@Input
	public abstract Property<String> getArtifactVersion();

	@Input
	@Optional
	public abstract Property<String> getBox();

	/** Where the box name came from, for the log. */
	@Input
	@Optional
	public abstract Property<String> getBoxSource();

	@Input
	public abstract Property<String> getServerUrl();

	@Input
	public abstract Property<String> getServerSecret();

	@TaskAction
	public void install() {

		Path archive = getArchive().get().getAsFile().toPath();
		ArtifactKind kind = getKind().get();
		String box = getBox().getOrNull();

		try {
			BoxTarget target = BoxTarget.resolve(box, getServerUrl().getOrNull(), getServerSecret().getOrNull());
			ManagerClient client = ManagerClient.of(target, message -> getLogger().lifecycle(message));

			getLogger().lifecycle("installing {} {}@{} on {}", kind.key(), getArtifactName().get(), getArtifactVersion().get(),
					box == null || box.isBlank() ? target.url() : "box '" + box + "' (" + getBoxSource().getOrElse("named") + ") at " + target.url());

			Map<String, Object> job = client.install(kind, getArtifactName().get(), getArtifactVersion().get(), archive, ManagerClient.INSTALL_TIMEOUT);
			getLogger().lifecycle("installed {}@{} (job {})", getArtifactName().get(), getArtifactVersion().get(), job.get("id"));
		} catch (BoxClientException e) {
			throw new GradleException(e.getMessage(), e);
		}

	}

}
