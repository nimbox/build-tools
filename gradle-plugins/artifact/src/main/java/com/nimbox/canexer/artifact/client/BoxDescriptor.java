package com.nimbox.canexer.artifact.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * The box descriptor at {@code ~/.nimbox/boxes/<box>/} ({@code NIMBOX_BOXES}
 * moves the directory): the manifest {@code box.json} with the server
 * {@code url} and the box facts, beside the seed files the box tasks read.
 * A missing manifest is an empty one.
 */
public final class BoxDescriptor {

	public static final String MANIFEST = "box.json";

	private final String box;
	private final Path directory;
	private final Map<String, Object> manifest;

	private BoxDescriptor(String box, Path directory, Map<String, Object> manifest) {
		this.box = box;
		this.directory = directory;
		this.manifest = manifest;
	}

	/** {@code NIMBOX_BOXES}, else {@code ~/.nimbox/boxes}. */
	public static Path boxesDirectory() {
		String boxes = System.getenv("NIMBOX_BOXES");
		return boxes != null && !boxes.isBlank() ? Paths.get(boxes) : Paths.get(System.getProperty("user.home"), ".nimbox", "boxes");
	}

	public static BoxDescriptor load(String box) {
		return load(boxesDirectory(), box);
	}

	public static BoxDescriptor load(Path boxes, String box) {
		if (box == null || box.isBlank()) {
			throw new BoxClientException("a box name is required");
		}
		Path directory = boxes.resolve(box);
		Path file = directory.resolve(MANIFEST);
		Map<String, Object> manifest = Map.of();
		if (Files.isRegularFile(file)) {
			try {
				manifest = Json.object(Files.readString(file, StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new BoxClientException("unable to read " + file, e);
			}
			if (manifest == null) {
				throw new BoxClientException(file + " is not a JSON object");
			}
		}
		return new BoxDescriptor(box, directory, manifest);
	}

	public String box() {
		return box;
	}

	public Path directory() {
		return directory;
	}

	public Path manifestFile() {
		return directory.resolve(MANIFEST);
	}

	public Map<String, Object> manifest() {
		return manifest;
	}

	/** The server URL named in the manifest, without a trailing slash, or null. */
	public String url() {
		String url = Json.string(manifest, "url");
		return url == null || url.isBlank() ? null : url.replaceAll("/+$", "");
	}

	/** A string fact of the manifest ({@code country}, {@code currency}, {@code locale}, {@code zone}), or null. */
	public String fact(String key) {
		return Json.string(manifest, key);
	}

	/** A list of the manifest ({@code applications}, {@code connectors}), or empty. */
	public List<String> list(String key) {
		return Json.array(manifest, key).stream().map(Object::toString).toList();
	}

	/** A file beside the manifest. */
	public Path file(String name) {
		return directory.resolve(name);
	}

	/** Reads a file beside the manifest, failing with a message that names it. */
	public String read(String name) {
		Path file = file(name);
		if (!Files.isRegularFile(file)) {
			throw new BoxClientException("box '" + box + "' has no " + name + " in its descriptor " + directory);
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BoxClientException("unable to read " + file, e);
		}
	}

}
