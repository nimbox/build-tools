package com.nimbox.canexer.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Uploads the archive to {@code POST /server/manager/install} as a multipart
 * request and polls the job until it settles. The build fails with the job's
 * cause when the install does.
 */
public abstract class InstallToServerTask extends DefaultTask {

	private static final Duration TIMEOUT = Duration.ofSeconds(180);
	private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern STATE = Pattern.compile("\"state\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern CAUSE = Pattern.compile("\"cause\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	@InputFile
	public abstract RegularFileProperty getArchive();

	@Input
	public abstract Property<ArtifactKind> getKind();

	@Input
	public abstract Property<String> getArtifactName();

	@Input
	public abstract Property<String> getArtifactVersion();

	@Input
	public abstract Property<String> getServerUrl();

	@Input
	public abstract Property<String> getServerSecret();

	@TaskAction
	public void install() throws Exception {

		Path archive = getArchive().get().getAsFile().toPath();
		String sha256 = sha256(archive);
		String base = getServerUrl().get().replaceAll("/+$", "");

		String manifest = String.format(Locale.ROOT,
				"{\"kind\":\"%s\",\"artifactName\":\"%s\",\"artifactVersion\":\"%s\",\"sha256\":\"%s\"}",
				getKind().get().name().toLowerCase(Locale.ROOT), getArtifactName().get(), getArtifactVersion().get(), sha256);

		getLogger().lifecycle("installing {} {}@{} on {}", getKind().get().name().toLowerCase(Locale.ROOT),
				getArtifactName().get(), getArtifactVersion().get(), base);

		HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

		String boundary = "canexer-" + Long.toHexString(System.nanoTime());
		byte[] body = multipart(boundary, manifest, archive);
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/server/manager/install"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.timeout(TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		authorize(request);

		HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new GradleException("server refused the credentials (" + response.statusCode() + "): set CANEXER_SERVER_SECRET");
		}
		if (response.statusCode() >= 400) {
			throw new GradleException("install refused (" + response.statusCode() + "): " + message(response.body()));
		}

		String id = find(ID, response.body());
		if (id == null) {
			throw new GradleException("server answered without a job: " + response.body());
		}

		// Poll

		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		String state = find(STATE, response.body());
		String job = response.body();
		while (!"DONE".equals(state) && !"FAILED".equals(state)) {
			if (System.nanoTime() > deadline) {
				throw new GradleException("install job " + id + " did not settle within " + TIMEOUT.toSeconds() + "s (last state " + state + ")");
			}
			Thread.sleep(1000);
			HttpRequest.Builder poll = HttpRequest.newBuilder(URI.create(base + "/server/manager/jobs/" + id)).GET();
			authorize(poll);
			job = client.send(poll.build(), HttpResponse.BodyHandlers.ofString()).body();
			state = find(STATE, job);
		}

		if ("FAILED".equals(state)) {
			throw new GradleException("install failed: " + unescape(find(CAUSE, job)));
		}
		getLogger().lifecycle("installed {}@{} (job {})", getArtifactName().get(), getArtifactVersion().get(), id);

	}

	// Helpers

	private void authorize(HttpRequest.Builder request) {
		String secret = getServerSecret().getOrElse("");
		if (!secret.isBlank()) {
			request.header("Authorization", "Bearer " + secret);
		}
	}

	private static byte[] multipart(String boundary, String manifest, Path archive) throws IOException {

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String crlf = "\r\n";

		out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(("Content-Disposition: form-data; name=\"manifest\"" + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(("Content-Type: application/json; charset=utf-8" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(manifest.getBytes(StandardCharsets.UTF_8));
		out.write(crlf.getBytes(StandardCharsets.UTF_8));

		out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(("Content-Disposition: form-data; name=\"artifact\"; filename=\"" + archive.getFileName() + "\"" + crlf)
				.getBytes(StandardCharsets.UTF_8));
		out.write(("Content-Type: application/octet-stream" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(Files.readAllBytes(archive));
		out.write(crlf.getBytes(StandardCharsets.UTF_8));

		out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();

	}

	private static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(Files.readAllBytes(file));
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String find(Pattern pattern, String json) {
		if (json == null) {
			return null;
		}
		Matcher matcher = pattern.matcher(json);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String message(String body) {
		String error = find(ERROR, body);
		if (error != null) {
			return unescape(error);
		}
		String cause = find(CAUSE, body);
		return cause != null ? unescape(cause) : body;
	}

	private static String unescape(String value) {
		return value == null ? "unknown cause" : value.replace("\\\"", "\"").replace("\\\\", "\\");
	}

}
