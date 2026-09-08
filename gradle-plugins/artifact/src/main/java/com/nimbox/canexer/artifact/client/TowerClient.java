package com.nimbox.canexer.artifact.client;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tower's GraphQL API for a crew member: box tokens, adoption and
 * activation. The tower is {@code NIMBOX_TOWER_URL}; the login is
 * {@code NIMBOX_TOWER_TOKEN}, else the session {@code tower login} keeps in
 * {@code ~/.nimbox/tower.json}. A missing login fails only when the tower is
 * actually asked for something.
 */
public final class TowerClient {

	public static final String DEFAULT_URL = "https://tower.nimbox.com";
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	/** What the tower hands back at activation: the token and the box facts. */
	public record Activation(String token, String country, String currency, String locale, String zone, boolean delivered) {

		public Map<String, Object> payload() {

			Map<String, Object> payload = new LinkedHashMap<>();
			
			payload.put("token", token);
			payload.put("country", country);
			payload.put("currency", currency);
			payload.put("locale", locale);
			payload.put("zone", zone);
			
			return payload;

		}

	}

	private final String url;
	private final String login;
	private final String loginSource;

	private final HttpClient client;

	public TowerClient(String url, String login, String loginSource, HttpClient client) {

		this.url = url.replaceAll("/+$", "");
		this.login = login;
		this.loginSource = loginSource;

		this.client = client;

	}

	public static TowerClient fromEnvironment(HttpClient client) {

		String url = System.getenv("NIMBOX_TOWER_URL");
		String login = System.getenv("NIMBOX_TOWER_TOKEN");
		
		String source = "NIMBOX_TOWER_TOKEN";

		if (login == null || login.isBlank()) {
			Path session = Paths.get(System.getProperty("user.home"), ".nimbox", "tower.json");
			source = session.toString();
			login = null;
			if (Files.isRegularFile(session)) {
				try {
					login = Json.string(Json.object(Files.readString(session, StandardCharsets.UTF_8)), "token");
				} catch (IOException e) {
					throw new BoxClientException("unable to read " + session, e);
				}
			}
		}

		return new TowerClient(url == null || url.isBlank() ? DEFAULT_URL : url, login, source, client);

	}

	public String url() {
		return url;
	}

	/** A short-lived token for the box, scoped to the verbs ({@code read}, {@code write}). */
	public String token(String box, List<String> verbs) {

		Map<String, Object> result = mutation("token",
				"mutation($name: String!, $verbs: [TowerVerb!]!) { tower { boxes { token(name: $name, verbs: $verbs) { result { token } errors { name code value } } } } }",
				Map.of("name", box, "verbs", verbs), "the tower refused a token for '" + box + "'");

		String token = Json.string(result, "token");
		if (token == null) {
			throw new BoxClientException("the tower returned no token for '" + box + "'");
		}

		return token;

	}

	/** Binds the machine showing the label to the box; returns the box name. */
	public String adopt(String box, String label) {

		Map<String, Object> result = mutation("adopt",
				"mutation($name: String!, $label: String!) { tower { boxes { adopt(name: $name, label: $label) { result { name } errors { name code value } } } } }",
				Map.of("name", box, "label", label), "tower adoption of '" + box + "' failed");

		String name = Json.string(result, "name");
		if (name == null) {
			throw new BoxClientException("tower adoption returned no result for '" + box + "'");
		}

		return name;

	}

	/** Activates the box: the tower mints its token and delivers it over the VPN when it can. */
	public Activation activate(String box) {

		Map<String, Object> result = mutation("activate",
				"mutation($name: String!) { tower { boxes { activate(name: $name) { result { token delivered box { name country currency locale zone } } errors { name code value } } } } }",
				Map.of("name", box), "tower activation of '" + box + "' failed");
		
		String token = Json.string(result, "token");
		Map<String, Object> facts = Json.object(result, "box");
		if (token == null || facts == null) {
			throw new BoxClientException("tower activation returned no result for '" + box + "'");
		}

		return new Activation(token, 
			Json.string(facts, "country"),
			Json.string(facts, "currency"),
			Json.string(facts, "locale"),
			Json.string(facts, "zone"), 
			Boolean.TRUE.equals(result.get("delivered")));

	}

	/**
	 * Runs a {@code tower.boxes.<field>} mutation and returns its
	 * {@code result}, failing with the tower's errors when it refused.
	 */
	public Map<String, Object> mutation(String field, String query, Map<String, Object> variables, String refusal) {

		if (login == null) {
			throw new BoxClientException("the tower at " + url + " needs a login and there is none at " + loginSource + "; run: tower login <username>");
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/graphql"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + login)
				.timeout(TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", query, "variables", variables)), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new BoxClientException("unable to reach the tower at " + url, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BoxClientException("interrupted while waiting for the tower at " + url, e);
		}
		
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new BoxClientException("the tower at " + url + " refused the login (" + response.statusCode() + "); run: tower login <username>");
		}
		if (response.statusCode() >= 400) {
			throw new BoxClientException("the tower at " + url + " answered " + response.statusCode() + ": " + response.body());
		}

		Map<String, Object> body = Json.object(response.body());
		if (body == null) {
			throw new BoxClientException("the tower at " + url + " did not answer JSON: " + response.body());
		}

		List<Object> errors = Json.array(body, "errors");
		if (!errors.isEmpty()) {
			throw new BoxClientException(refusal + ": " + Json.write(errors));
		}

		Map<String, Object> payload = Json.object(Json.object(Json.object(Json.object(body, "data"), "tower"), "boxes"), field);
		errors = Json.array(payload, "errors");
		if (!errors.isEmpty()) {
			throw new BoxClientException(refusal + ": " + Json.write(errors));
		}

		Map<String, Object> result = Json.object(payload, "result");
		if (result == null) {
			throw new BoxClientException(refusal + ": no result in " + response.body());
		}

		return result;

	}

}
