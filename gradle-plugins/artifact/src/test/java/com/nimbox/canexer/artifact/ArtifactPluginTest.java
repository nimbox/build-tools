package com.nimbox.canexer.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plugin on real builds: every kind's archive states the four attributes,
 * a zip's in the distribution's own jar.
 */
class ArtifactPluginTest {

	@TempDir
	Path project;

	private BuildResult build(String name, String kind, String configuration, String plugins, String... arguments) throws IOException {

		Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"" + name + "\"\n");
		Files.writeString(project.resolve("build.gradle.kts"), """
				plugins {
				%s
					id("com.nimbox.canexer.artifact")
				}
				version = "1.2.3"
				canexerArtifact {
					kind.set(com.nimbox.canexer.artifact.ArtifactKind.%s)
				%s
				}
				""".formatted(plugins, kind, configuration));

		return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(arguments).build();

	}

	private static Attributes zipManifest(Path zip, String top) throws IOException {
		try (ZipFile file = new ZipFile(zip.toFile())) {
			ZipEntry own = file.getEntry(top + "/lib/" + top + ".jar");
			assertNotNull(own, "the distribution holds its own jar");
			assertNull(file.getEntry(top + "/" + JarFile.MANIFEST_NAME), "no loose manifest");
			try (ZipInputStream jar = new ZipInputStream(file.getInputStream(own))) {
				for (ZipEntry entry = jar.getNextEntry(); entry != null; entry = jar.getNextEntry()) {
					if (entry.getName().equals(JarFile.MANIFEST_NAME)) {
						return new Manifest(jar).getMainAttributes();
					}
				}
			}
			throw new AssertionError("the jar has no manifest");
		}
	}

	@Test
	void whenServer_thenItsOwnJarStatesTheFour() throws IOException {

		build("canexer-server", "SERVER", "", "	application", "distZip");

		Attributes attributes = zipManifest(project.resolve("build/distributions/canexer-server-1.2.3.zip"), "canexer-server-1.2.3");
		assertEquals("server", attributes.getValue("Nimbox-Kind"));
		assertEquals("canexer-server", attributes.getValue("Nimbox-Name"));
		assertEquals("1.2.3", attributes.getValue("Nimbox-Version"));
		assertEquals("server", attributes.getValue("Nimbox-Provides"));

	}

	@Test
	void whenBootstrap_thenItsOwnJarStatesItsKindAndItHasNoInstallTask() throws IOException {

		BuildResult tasks = build("canexer-bootstrap", "BOOTSTRAP", "", "	application", "distZip", "tasks", "--all");

		Attributes attributes = zipManifest(project.resolve("build/distributions/canexer-bootstrap-1.2.3.zip"), "canexer-bootstrap-1.2.3");
		assertEquals("bootstrap", attributes.getValue("Nimbox-Kind"));
		assertEquals("bootstrap", attributes.getValue("Nimbox-Provides"));
		assertFalse(tasks.getOutput().contains(ArtifactPlugin.INSTALL_TASK));

	}

	@Test
	void whenApplication_thenItsWarStatesTheFour() throws IOException {

		BuildResult tasks = build("canexer-application-ar", "APPLICATION", "	provides.set(\"ar\")", "	war", "war", "tasks", "--all");

		try (JarFile war = new JarFile(project.resolve("build/libs/canexer-application-ar-1.2.3.war").toFile())) {
			Attributes attributes = war.getManifest().getMainAttributes();
			assertEquals("application", attributes.getValue("Nimbox-Kind"));
			assertEquals("canexer-application-ar", attributes.getValue("Nimbox-Name"));
			assertEquals("1.2.3", attributes.getValue("Nimbox-Version"));
			assertEquals("ar", attributes.getValue("Nimbox-Provides"));
		}
		assertTrue(tasks.getOutput().contains(ArtifactPlugin.INSTALL_TASK));

	}

}
