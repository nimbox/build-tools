package com.nimbox.canexer.artifact.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalBoxTest {

	@TempDir
	Path temporary;

	@Test
	void whenEnvironmentFileNamesTheVolume_thenBoxAndLabelRead() throws IOException {
		Path data = temporary.resolve("volume");
		Files.createDirectories(data);
		Files.writeString(data.resolve("box"), "testone\n");
		Files.writeString(data.resolve("label"), "communicable-banks\n");
		Path environment = temporary.resolve(".env");
		Files.writeString(environment, "CANEXER_URL=https://x\nCANEXER_DATA=\"" + data + "\"  # the volume\n");
		Path resolved = LocalBox.dataPath(environment);
		if (System.getenv("CANEXER_DATA") == null) {
			assertEquals(data, resolved);
		}
		assertEquals("testone", LocalBox.name(data));
		assertEquals("communicable-banks", LocalBox.label(data));
	}

	@Test
	void whenVolumeIsBlank_thenNoName() throws IOException {
		Path data = temporary.resolve("blank");
		Files.createDirectories(data);
		assertNull(LocalBox.name(data));
		assertNull(LocalBox.label(data));
		assertNull(LocalBox.name(null));
	}

	@Test
	void whenNoEnvironmentFile_thenNoVolume() {
		if (System.getenv("CANEXER_DATA") == null) {
			assertNull(LocalBox.dataPath(temporary.resolve("missing")));
			assertNull(LocalBox.dataPath(null));
		}
	}

}
