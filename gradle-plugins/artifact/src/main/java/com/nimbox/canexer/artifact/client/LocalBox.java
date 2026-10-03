package com.nimbox.canexer.artifact.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The box this machine runs: its data volume and the machine the
 * {@code nimbox-atlas} agent keeps there. The volume is {@code CANEXER_DATA}
 * in the environment, else the same variable in an environment file
 * ({@code canexer-server/.env} in the canexer repository);
 * {@code <data>/nimbox/machine.name} is the identity's name once adopted,
 * whose first label names the box, and {@code <data>/nimbox/machine.label}
 * is the label the agent chose at its first boot.
 */
public final class LocalBox {

	public static final String MACHINE = "nimbox";
	public static final String NAME = "machine.name";
	public static final String LABEL = "machine.label";
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

	/** @return the box's short name, the first label of the machine's name, or null when the volume is blank or unknown */
	public static String name(Path data) {
		String name = trimmed(data, NAME);
		return name == null ? null : name.split("\\.", 2)[0];
	}

	/** @return the label the agent chose, or null */
	public static String label(Path data) {
		return trimmed(data, LABEL);
	}

	private static String trimmed(Path data, String file) {

		if (data == null) {
			return null;
		}

		Path path = data.resolve(MACHINE).resolve(file);
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
