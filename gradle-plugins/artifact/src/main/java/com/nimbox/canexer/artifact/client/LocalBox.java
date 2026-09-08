package com.nimbox.canexer.artifact.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The box this machine runs: its data volume and the files adoption leaves
 * there. The volume is {@code CANEXER_DATA} in the environment, else the same
 * variable in an environment file ({@code canexer-server/.env} in the canexer
 * repository); {@code <data>/box} names the box and {@code <data>/label} is
 * the label the controller wrote at its first boot.
 */
public final class LocalBox {

	public static final String BOX = "box";
	public static final String LABEL = "label";
	private static final Pattern DATA = Pattern.compile("^\\s*CANEXER_DATA\\s*=\\s*(.*?)\\s*(?:#.*)?$");

	private LocalBox() {
	}

	/** @return the data volume, or null when neither the environment nor the file names one */
	public static Path dataPath(Path environmentFile) {

		String data = System.getenv("CANEXER_DATA");
		if (data == null || data.isBlank()) {
			data = fromFile(environmentFile);
		}

		return data == null ? null : Paths.get(data);

	}

	private static String fromFile(Path environmentFile) {

		if (environmentFile == null || !Files.isRegularFile(environmentFile)) {
			return null;
		}
		
		String value = null;
		try {
			for (String line : Files.readAllLines(environmentFile, StandardCharsets.UTF_8)) {
				Matcher matcher = DATA.matcher(line);
				if (matcher.matches()) {
					value = matcher.group(1).replaceAll("^[\"']|[\"']$", "");
				}
			}
		} catch (IOException e) {
			throw new BoxClientException("unable to read " + environmentFile, e);
		}

		return value == null || value.isBlank() ? null : value;

	}

	/** @return the box name adoption wrote on the volume, or null when the volume is blank or unknown */
	public static String name(Path data) {
		return trimmed(data, BOX);
	}

	/** @return the label the controller wrote, or null */
	public static String label(Path data) {
		return trimmed(data, LABEL);
	}

	private static String trimmed(Path data, String file) {

		if (data == null) {
			return null;
		}

		Path path = data.resolve(file);
		if (!Files.isRegularFile(path)) {
			return null;
		}

		try {
			String value = Files.readString(path, StandardCharsets.UTF_8).trim();
			return value.isEmpty() ? null : value;
		} catch (IOException e) {
			throw new BoxClientException("unable to read " + path, e);
		}

	}

}
