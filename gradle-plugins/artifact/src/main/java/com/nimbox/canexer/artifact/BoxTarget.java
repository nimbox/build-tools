package com.nimbox.canexer.artifact;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

/**
 * Where an install goes and how it is let in. A box is named with
 * {@code -Pbox=<name>}, {@code CANEXER_BOX}, or the composite's
 * {@code canexer.box} (the development box, read off the data volume); its
 * server URL comes from the box descriptor at
 * {@code ~/.nimbox/boxes/<name>/box.json} ({@code NIMBOX_BOXES} moves the
 * directory), else from {@code CANEXER_URL}, else localhost.
 * <p>
 * A DEVELOPMENT or INTEGRATION server lets the upload in as is. A box whose
 * manager plane takes tower tokens only gets one from the tower — five
 * minutes, scoped to the box, {@code read} and {@code write} — signed for the
 * session the tower CLI keeps in {@code ~/.nimbox/tower.json} at
 * {@code tower login}. {@code CANEXER_SERVER_SECRET}, when set, is presented
 * as the bearer instead and the tower is never asked.
 */
final class BoxTarget {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final String DEFAULT_URL = "http://localhost:8088";
	private static final String DEFAULT_TOWER_URL = "https://tower.nimbox.com";
	private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ERRORS = Pattern.compile("\"errors\"\\s*:\\s*(\\[[^\\]]*\\])");

	private final String box;
	private final String url;
	private final String secret;
	private final HttpClient client;
	private final Logger logger;

	private String bearer;

	private BoxTarget(String box, String url, String secret, HttpClient client, Logger logger) {
		this.box = box;
		this.url = url;
		this.secret = secret;
		this.client = client;
		this.logger = logger;
	}

	/**
	 * @param box the box name, or null for none
	 * @param urlOverride the server URL when no descriptor names one, or null
	 * @param secret an explicit bearer, or null
	 */
	static BoxTarget resolve(String box, String urlOverride, String secret, HttpClient client, Logger logger) {
		String url;
		if (box != null && !box.isBlank()) {
			// A named box goes where its descriptor says, never to a default: a
			// missing url would send the artifact to the wrong box.
			url = descriptorUrl(box);
			if (url == null) {
				throw new GradleException("box '" + box + "' has no url in its descriptor " + descriptorFile(box) + "; add one, or name no box to install on " + (urlOverride != null && !urlOverride.isBlank() ? urlOverride : DEFAULT_URL));
			}
		} else {
			url = urlOverride != null && !urlOverride.isBlank() ? urlOverride : DEFAULT_URL;
		}
		return new BoxTarget(box, url.replaceAll("/+$", ""), secret != null && !secret.isBlank() ? secret : null, client, logger);
	}

	String url() {
		return url;
	}

	/**
	 * Finds out how the manager plane answers before anything is uploaded, so
	 * a refused upload never travels twice: an explicit secret is kept; an open
	 * plane needs nothing; a refusing plane gets a tower token for the box.
	 */
	void authenticate() throws IOException, InterruptedException {
		if (secret != null) {
			bearer = secret;
			return;
		}
		HttpRequest probe = HttpRequest.newBuilder(URI.create(url + "/server/manager/status")).timeout(TIMEOUT).GET().build();
		int status;
		try {
			status = client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
			throw new GradleException("unable to reach the server at " + url + " (is it running?)", e);
		}
		if (status == 401 || status == 403) {
			if (box == null || box.isBlank()) {
				throw new GradleException("the manager plane at " + url + " takes tower tokens only: name the box with -Pbox=<name> so one can be obtained");
			}
			logger.lifecycle("the manager plane at {} takes tower tokens; asking the tower for one for '{}'", url, box);
			bearer = towerToken();
		}
	}

	/** Adds the bearer, when one is in play, to a request for the plane. */
	void authorize(HttpRequest.Builder request) {
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
	}

	// Descriptor

	private static Path descriptorFile(String box) {
		String boxes = System.getenv("NIMBOX_BOXES");
		Path directory = boxes != null && !boxes.isBlank() ? Paths.get(boxes) : Paths.get(System.getProperty("user.home"), ".nimbox", "boxes");
		return directory.resolve(box).resolve("box.json");
	}

	private static String descriptorUrl(String box) {
		Path manifest = descriptorFile(box);
		if (!Files.isRegularFile(manifest)) {
			return null;
		}
		try {
			Matcher matcher = URL.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
			return matcher.find() ? matcher.group(1) : null;
		} catch (IOException e) {
			throw new GradleException("unable to read " + manifest, e);
		}
	}

	// Tower

	private String towerToken() throws IOException, InterruptedException {

		Path session = Paths.get(System.getProperty("user.home"), ".nimbox", "tower.json");
		String login = null;
		if (Files.isRegularFile(session)) {
			Matcher matcher = TOKEN.matcher(Files.readString(session, StandardCharsets.UTF_8));
			login = matcher.find() ? matcher.group(1) : null;
		}
		if (login == null) {
			throw new GradleException("the manager plane at " + url + " takes tower tokens only and there is no tower login at " + session + "; run: tower login <username>");
		}

		String towerUrl = firstNonBlank(System.getenv("NIMBOX_TOWER_URL"), System.getenv("TOWER_URL"), DEFAULT_TOWER_URL).replaceAll("/+$", "");
		String query = "mutation($name: String!) { tower { boxes { token(name: $name, verbs: [read, write]) { result { token } errors { name code value } } } } }";
		String body = "{\"query\":" + quote(query) + ",\"variables\":{\"name\":" + quote(box) + "}}";
		HttpRequest request = HttpRequest.newBuilder(URI.create(towerUrl + "/graphql"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + login)
				.timeout(TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new GradleException("unable to reach the tower at " + towerUrl, e);
		}
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new GradleException("the tower at " + towerUrl + " refused the login (" + response.statusCode() + "); run: tower login <username>");
		}
		if (response.statusCode() >= 400) {
			throw new GradleException("the tower at " + towerUrl + " answered " + response.statusCode() + ": " + response.body());
		}

		Matcher errors = ERRORS.matcher(response.body());
		if (errors.find() && !"[]".equals(errors.group(1).replaceAll("\\s", ""))) {
			throw new GradleException("the tower refused a token for '" + box + "': " + errors.group(1));
		}
		Matcher token = TOKEN.matcher(response.body());
		if (!token.find()) {
			throw new GradleException("the tower returned no token for '" + box + "': " + response.body());
		}
		return token.group(1);

	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
