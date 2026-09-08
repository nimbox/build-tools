package com.nimbox.canexer.artifact.client;

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
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.nimbox.canexer.artifact.ArtifactKind;

/**
 * A box's manager plane ({@code /server/manager/*}): JSON in and out, the
 * multipart install with its job, and the server mode. Credentials settle
 * before anything travels: an explicit secret is kept; an open plane needs
 * nothing; a plane that refuses gets a tower token for the box, scoped
 * {@code read} and {@code write}, so a refused upload never travels twice.
 */
public final class ManagerClient {

	public static final String PLANE = "/server/manager";
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
	public static final Duration INSTALL_TIMEOUT = Duration.ofSeconds(180);

	/** What the plane answered: the status, the body as an object when it was one, and the text. */
	public record Answer(int status, Map<String, Object> body, String text) {

		public boolean ok() {
			return status >= 200 && status < 300;
		}

		/** The plane's {@code error} or {@code cause}, else the whole text. */
		public String message() {

			String error = Json.string(body, "error");
			if (error != null) {
				return error;
			}

			String cause = Json.string(body, "cause");
			return cause != null ? cause : text;

		}

	}

	private final BoxTarget target;
	private final HttpClient client;
	private final TowerClient tower;
	private final Consumer<String> log;
	private Duration pollInterval = Duration.ofSeconds(1);

	private boolean authenticated;
	private String bearer;

	public ManagerClient(BoxTarget target, HttpClient client, TowerClient tower, Consumer<String> log) {
		this.target = target;
		this.client = client;
		this.tower = tower;
		this.log = log;
	}

	/** The default client: no proxy, the tower from the environment. */
	public static ManagerClient of(BoxTarget target, Consumer<String> log) {
		HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
		return new ManagerClient(target, client, TowerClient.fromEnvironment(client), log);
	}

	public ManagerClient withPollInterval(Duration interval) {
		this.pollInterval = interval;
		return this;
	}

	public BoxTarget target() {
		return target;
	}

	public String url() {
		return target.url();
	}

	// Mode

	/** @return the server's mode ({@code PROVISION}, {@code RUN}, ...), or null when it does not answer */
	public String mode() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(target.url() + "/server/mode")).timeout(Duration.ofSeconds(5)).GET().build();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 ? Json.string(Json.object(response.body()), "mode") : null;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	public String waitForMode(String mode, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		String last = null;
		while (System.nanoTime() < deadline) {
			last = mode();
			if (mode.equals(last)) {
				return last;
			}
			pause();
		}
		throw new BoxClientException("timed out waiting for mode " + mode + " at " + target.url() + " (last: " + (last == null ? "no answer" : last) + ")");
	}

	// Plane

	public Answer get(String path) {
		authenticate();
		return send(builder(path).GET());
	}

	public Answer post(String path, Object body) {
		authenticate();
		return send(builder(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)));
	}

	/** A POST whose refusal is the failure: anything but 2xx throws with the plane's message. */
	public Answer postOrFail(String path, Object body, String what) {
		Answer answer = post(path, body);
		if (!answer.ok()) {
			throw new BoxClientException(what + " refused (" + answer.status() + "): " + answer.message());
		}
		return answer;
	}

	private HttpRequest.Builder builder(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.url() + PLANE + path)).timeout(DEFAULT_TIMEOUT);
		if (bearer != null) {
			builder.header("Authorization", "Bearer " + bearer);
		}
		return builder;
	}

	private Answer send(HttpRequest.Builder builder) {
		HttpResponse<String> response;
		try {
			response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new BoxClientException("unable to reach the manager plane at " + target.url() + " (is the server running?)", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BoxClientException("interrupted while waiting for " + target.url(), e);
		}
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new BoxClientException("the manager plane at " + target.url() + " refused the credentials (" + response.statusCode() + ")"
					+ (target.box() == null ? "; name the box with -Pbox=<name> so a tower token can be obtained" : ""));
		}
		return new Answer(response.statusCode(), Json.object(response.body()), response.body());
	}

	/**
	 * Finds out how the plane answers before anything else is sent. Runs once.
	 */
	public void authenticate() {
		if (authenticated) {
			return;
		}
		authenticated = true;
		if (target.secret() != null) {
			bearer = target.secret();
			return;
		}
		HttpRequest probe = HttpRequest.newBuilder(URI.create(target.url() + PLANE + "/status")).timeout(DEFAULT_TIMEOUT).GET().build();
		int status;
		try {
			status = client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
			throw new BoxClientException("unable to reach the manager plane at " + target.url() + " (is the server running?)", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BoxClientException("interrupted while waiting for " + target.url(), e);
		}
		if (status == 401 || status == 403) {
			if (target.box() == null) {
				throw new BoxClientException("the manager plane at " + target.url() + " takes tower tokens only: name the box with -Pbox=<name> so one can be obtained");
			}
			log.accept("the manager plane at " + target.url() + " takes tower tokens; asking the tower at " + tower.url() + " for one for '" + target.box() + "'");
			bearer = tower.token(target.box(), List.of("read", "write"));
		}
	}

	// Install

	/**
	 * Uploads the archive as a multipart install and waits for the job to
	 * settle. A server install restarts the box: the plane stops answering
	 * while it does, and the poll keeps waiting until it is back and the job
	 * is done.
	 *
	 * @return the job document, {@code DONE}
	 * @throws BoxClientException when the install is refused, fails, or does not settle in time
	 */
	public Map<String, Object> install(ArtifactKind kind, String artifactName, String artifactVersion, Path archive, Duration timeout) {

		authenticate();

		Map<String, Object> manifest = new LinkedHashMap<>();

		manifest.put("kind", kind.key());
		manifest.put("artifactName", artifactName);
		manifest.put("artifactVersion", artifactVersion);
		manifest.put("artifactSha256", sha256(archive));

		String boundary = "canexer-" + Long.toHexString(System.nanoTime());
		byte[] body;
		try {
			body = multipart(boundary, Json.write(manifest), archive);
		} catch (IOException e) {
			throw new BoxClientException("unable to read " + archive, e);
		}
		Answer answer = send(builder("/install").timeout(timeout)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)));
		if (!answer.ok()) {
			throw new BoxClientException("install refused (" + answer.status() + "): " + answer.message());
		}
		Map<String, Object> job = job(answer.body());
		String id = Json.string(job, "id");
		if (id == null) {
			throw new BoxClientException("the manager plane answered without a job: " + answer.text());
		}

		long deadline = System.nanoTime() + timeout.toNanos();
		String state = Json.string(job, "state");
		boolean restarting = false;
		while (!"DONE".equals(state) && !"FAILED".equals(state)) {
			if (System.nanoTime() > deadline) {
				throw new BoxClientException("install job " + id + " did not settle within " + timeout.toSeconds() + "s (last state " + state + ")");
			}
			pause();
			try {
				Answer poll = send(builder("/jobs/" + id).GET());
				if (poll.ok() && poll.body() != null) {
					job = job(poll.body());
					state = Json.string(job, "state");
				}
			} catch (BoxClientException e) {
				if (kind != ArtifactKind.SERVER) {
					throw e;
				}
				if (!restarting) {
					log.accept("the server is restarting into " + artifactVersion + "; waiting for it to come back");
					restarting = true;
				}
			}
		}
		if ("FAILED".equals(state)) {
			throw new BoxClientException("install failed: " + Json.string(job, "cause"));
		}
		return job;

	}

	// Helpers

	/** The install answer wraps the job as {@code {job}}; the job endpoint answers the job itself. */
	private static Map<String, Object> job(Map<String, Object> document) {
		Map<String, Object> wrapped = Json.object(document, "job");
		return wrapped != null ? wrapped : document;
	}

	private void pause() {
		try {
			Thread.sleep(pollInterval.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BoxClientException("interrupted while waiting for " + target.url(), e);
		}
	}

	public static String sha256(Path file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(Files.readAllBytes(file));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException | IOException e) {
			throw new BoxClientException("unable to digest " + file, e);
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

}
