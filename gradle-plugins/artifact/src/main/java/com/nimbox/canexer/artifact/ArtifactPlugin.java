package com.nimbox.canexer.artifact;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.jvm.tasks.Jar;

/**
 * Stamps the {@code Nimbox-*} manifest on the artifact archive and registers
 * {@code installToServer}, which uploads the archive to a canexer box through
 * its control plane: {@code -Pbox=<name>} names the box (its descriptor gives
 * the URL, the tower a token when the box asks for one); without a box,
 * {@code CANEXER_URL} or localhost.
 */
public abstract class ArtifactPlugin implements Plugin<Project> {

	public static final String EXTENSION = "canexerArtifact";
	public static final String INSTALL_TASK = "installToServer";

	@Override
	public void apply(Project project) {

		ArtifactExtension extension = project.getExtensions().create(EXTENSION, ArtifactExtension.class);
		extension.getVendor().convention("Nimbox Technologies LTD");
		extension.getArchiveTask().convention(extension.getKind().map(ArtifactKind::archiveTask));

		project.afterEvaluate(p -> {

			if (!extension.getKind().isPresent() || !extension.getProvides().isPresent()) {
				throw new IllegalStateException(EXTENSION + " needs kind and provides");
			}
			if (extension.getKind().get() == ArtifactKind.CONNECTOR && !extension.getPluginClass().isPresent()) {
				throw new IllegalStateException(EXTENSION + " needs pluginClass for a connector");
			}

			String taskName = extension.getArchiveTask().get();
			Jar archive = (Jar) p.getTasks().getByName(taskName);

			// Manifest

			archive.manifest(manifest -> manifest.attributes(attributes(p, extension)));

			// installToServer

			p.getTasks().register(INSTALL_TASK, InstallToServerTask.class, task -> {
				task.setGroup("canexer");
				task.setDescription("Uploads the artifact to a running canexer server and waits for it to install");
				task.dependsOn(archive);
				task.getArchive().set(archive.getArchiveFile());
				task.getKind().set(extension.getKind());
				task.getArtifactName().set(p.getName());
				task.getArtifactVersion().set(p.getVersion().toString());
				task.getBox().set(p.getProviders().gradleProperty("box").orElse(p.getProviders().environmentVariable("CANEXER_BOX")));
				task.getServerUrl().set(p.getProviders().environmentVariable("CANEXER_URL").orElse(""));
				task.getServerSecret().set(p.getProviders().environmentVariable("CANEXER_SERVER_SECRET").orElse(""));
			});

		});

	}

	private static Map<String, Object> attributes(Project project, ArtifactExtension extension) {

		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));

		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("Nimbox-Name", project.getName());
		attributes.put("Nimbox-Vendor", extension.getVendor().get());
		attributes.put("Nimbox-Version", project.getVersion().toString());
		attributes.put("Nimbox-Provides", extension.getProvides().get());
		if (extension.getPluginClass().isPresent()) {
			attributes.put("Nimbox-Plugin-Class", extension.getPluginClass().get());
		}
		if (extension.getRequires().isPresent()) {
			attributes.put("Nimbox-Requires", extension.getRequires().get());
		}
		attributes.put("Nimbox-Built-On", format.format(new Date()));
		return attributes;

	}

}
