package com.nimbox.canexer.artifact;

import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.jvm.tasks.Jar;

/**
 * Stamps the {@code Nimbox-*} manifest on a war, a jar, or a distribution's own
 * jar, and registers {@code installToServer}, which hands the archive to a canexer
 * development box through its manager plane, on the volume {@code CANEXER_DATA} in
 * the environment names. The box is {@code -Pbox=<name>}, else {@code CANEXER_BOX} in the
 * environment, else the development box the composite reads off this
 * machine's data volume ({@code canexer.box}); its descriptor gives the URL
 * and the tower a token when the box asks for one. Without any box,
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
		extension.getProvides().convention(extension.getKind().map(kind -> switch (kind) {
		case BOOTSTRAP, SERVER -> kind.key();
		default -> null;
		}));

		project.afterEvaluate(p -> {

			if (!extension.getKind().isPresent()) {
				throw new IllegalStateException(EXTENSION + " needs kind");
			}
			ArtifactKind kind = extension.getKind().get();
			if (!extension.getProvides().isPresent()) {
				throw new IllegalStateException(EXTENSION + " needs provides for " + kind.key());
			}
			if (kind == ArtifactKind.CONNECTOR && !extension.getPluginClass().isPresent()) {
				throw new IllegalStateException(EXTENSION + " needs pluginClass for a connector");
			}

			String taskName = extension.getArchiveTask().get();
			AbstractArchiveTask archive = (AbstractArchiveTask) p.getTasks().getByName(taskName);

			// Manifest

			// A distribution's manifest is its own jar's; a war or a jar has its own.

			boolean distribution = kind == ArtifactKind.BOOTSTRAP || kind == ArtifactKind.SERVER;
			Jar stamped = (Jar) (distribution ? p.getTasks().getByName(JavaPlugin.JAR_TASK_NAME) : archive);
			stamped.manifest(manifest -> manifest.attributes(attributes(p, extension)));

			// installToServer, which a bootstrap has no use for: a box runs the one its pin names.

			if (kind == ArtifactKind.BOOTSTRAP) {
				return;
			}

			p.getTasks().register(INSTALL_TASK, InstallToServerTask.class, task -> {
				task.setGroup("canexer");
				task.setDescription("Uploads the artifact to a running canexer server and waits for it to install");
				task.dependsOn(archive);
				task.getArchive().set(archive.getArchiveFile());
				task.getKind().set(extension.getKind());
				task.getArtifactName().set(extension.getProvides());
				task.getArtifactVersion().set(p.getVersion().toString());
				task.getBox().set(p.getProviders().gradleProperty("box")
						.orElse(p.getProviders().environmentVariable("CANEXER_BOX"))
						.orElse(p.getProviders().systemProperty("canexer.box")));
				task.getBoxSource().set(p.getProviders().gradleProperty("box").map(value -> "from -Pbox")
						.orElse(p.getProviders().environmentVariable("CANEXER_BOX").map(value -> "from CANEXER_BOX"))
						.orElse(p.getProviders().systemProperty("canexer.box").map(value -> "the development box")));
				task.getData().set(p.getProviders().environmentVariable("CANEXER_DATA"));
				task.getServerUrl().set(p.getProviders().environmentVariable("CANEXER_URL").orElse(""));
				task.getServerSecret().set(p.getProviders().environmentVariable("CANEXER_SERVER_SECRET").orElse(""));
			});

		});

	}

	private static Map<String, Object> attributes(Project project, ArtifactExtension extension) {

		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("Nimbox-Kind", extension.getKind().get().key());
		attributes.put("Nimbox-Name", project.getName());
		attributes.put("Nimbox-Version", project.getVersion().toString());
		attributes.put("Nimbox-Provides", extension.getProvides().get());
		attributes.put("Nimbox-Vendor", extension.getVendor().get());
		if (extension.getPluginClass().isPresent()) {
			attributes.put("Nimbox-Plugin-Class", extension.getPluginClass().get());
		}
		if (extension.getRequires().isPresent()) {
			attributes.put("Nimbox-Requires", extension.getRequires().get());
		}
		return attributes;

	}

}
