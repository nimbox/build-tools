package com.nimbox.canexer.artifact.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxDescriptorTest {

	@TempDir
	Path boxes;

	@Test
	void whenManifestPresent_thenUrlFactsAndListsRead() throws IOException {
		Files.createDirectories(boxes.resolve("testone"));
		Files.writeString(boxes.resolve("testone/box.json"), "{\"url\":\"http://localhost:8088/\",\"applications\":[\"ar\"],\"connectors\":[\"sbo\"],\"country\":\"DO\",\"locale\":\"es_DO\"}");
		Files.writeString(boxes.resolve("testone/users.json"), "[]");
		BoxDescriptor descriptor = BoxDescriptor.load(boxes, "testone");
		assertEquals("http://localhost:8088", descriptor.url());
		assertEquals("DO", descriptor.fact("country"));
		assertNull(descriptor.fact("currency"));
		assertEquals(List.of("ar"), descriptor.list("applications"));
		assertEquals(List.of(), descriptor.list("missing"));
		assertEquals("[]", descriptor.read("users.json"));
		BoxClientException e = assertThrows(BoxClientException.class, () -> descriptor.read("businesses.json"));
		assertTrue(e.getMessage().contains("businesses.json"));
	}

	@Test
	void whenManifestMissing_thenEmpty() {
		BoxDescriptor descriptor = BoxDescriptor.load(boxes, "nowhere");
		assertNull(descriptor.url());
		assertEquals(boxes.resolve("nowhere"), descriptor.directory());
	}

	@Test
	void whenManifestIsNotAnObject_thenFails() throws IOException {
		Files.createDirectories(boxes.resolve("broken"));
		Files.writeString(boxes.resolve("broken/box.json"), "[1]");
		assertThrows(BoxClientException.class, () -> BoxDescriptor.load(boxes, "broken"));
	}

	@Test
	void whenNoBoxNamed_thenTargetIsTheOverrideOrLocalhost() {
		assertEquals(BoxTarget.DEFAULT_URL, BoxTarget.resolve(null, "", null).url());
		assertEquals("http://box:8088", BoxTarget.resolve("", "http://box:8088/", "s").url());
		assertEquals("s", BoxTarget.resolve("", "http://box:8088/", "s").secret());
		assertNull(BoxTarget.resolve("", "http://box:8088/", "").secret());
	}

}
