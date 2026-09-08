package com.nimbox.canexer.artifact.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nimbox.canexer.artifact.ArtifactKind;

class ManagerClientTest {

	@TempDir
	Path temporary;

	FakePlane plane;
	HttpClient http;
	List<String> log;

	@BeforeEach
	void start() throws IOException {
		plane = new FakePlane();
		http = HttpClient.newHttpClient();
		log = new ArrayList<>();
	}

	@AfterEach
	void stop() {
		plane.close();
	}

	private ManagerClient client(String box, String login) {
		TowerClient tower = new TowerClient(plane.url(), login, "test", http);
		return new ManagerClient(BoxTarget.of(box, plane.url()), http, tower, log::add).withPollInterval(Duration.ofMillis(20));
	}

	@Test
	void whenOpenPlane_thenNoBearerTravels() {
		ManagerClient.Answer answer = client(null, null).get("/status");
		assertEquals(200, answer.status());
		assertEquals("testone", answer.body().get("box"));
		assertTrue(plane.requests.stream().noneMatch(request -> request.contains("Bearer")));
	}

	@Test
	void whenTokensOnly_thenTowerTokenIsPresented() {
		plane.tokensOnly = true;
		ManagerClient.Answer answer = client("testone", "login").get("/status");
		assertEquals(200, answer.status());
		assertTrue(plane.requests.contains("POST /graphql Bearer login"));
		assertTrue(plane.requests.contains("GET /server/manager/status Bearer " + FakePlane.TOKEN));
		assertEquals(1, log.size());
	}

	@Test
	void whenTokensOnlyWithoutBox_thenAsksForTheBoxName() {
		plane.tokensOnly = true;
		BoxClientException e = assertThrows(BoxClientException.class, () -> client(null, "login").get("/status"));
		assertTrue(e.getMessage().contains("-Pbox"));
	}

	@Test
	void whenTokensOnlyWithoutLogin_thenAsksForTowerLogin() {
		plane.tokensOnly = true;
		BoxClientException e = assertThrows(BoxClientException.class, () -> client("testone", null).get("/status"));
		assertTrue(e.getMessage().contains("tower login"));
	}

	@Test
	void whenInstalled_thenManifestCarriesArtifactSha256AndJobSettles() throws IOException {
		Path archive = temporary.resolve("ar-1.0.war");
		Files.writeString(archive, "not really a war");
		Map<String, Object> job = client(null, null).install(ArtifactKind.APPLICATION, "canexer-application-ar", "1.0", archive, Duration.ofSeconds(5));
		assertEquals("DONE", job.get("state"));
		String upload = plane.bodies.get(plane.requests.indexOf("POST /server/manager/install"));
		assertTrue(upload.contains("\"kind\":\"application\""));
		assertTrue(upload.contains("\"artifactSha256\":\"" + ManagerClient.sha256(archive) + "\""));
		assertFalse(upload.contains("\"sha256\""));
		assertTrue(upload.contains("filename=\"ar-1.0.war\""));
	}

	@Test
	void whenServerInstallRestarts_thenPollSurvivesTheGap() throws IOException {
		plane.serverRestart = true;
		Path archive = temporary.resolve("canexer-server-0.4.0.zip");
		Files.writeString(archive, "zip");
		Map<String, Object> job = client(null, null).install(ArtifactKind.SERVER, "canexer-server", "0.4.0", archive, Duration.ofSeconds(5));
		assertEquals("DONE", job.get("state"));
		assertTrue(log.stream().anyMatch(line -> line.contains("restarting")));
	}

	@Test
	void whenApplicationInstallLosesThePlane_thenItFails() throws IOException {
		plane.serverRestart = true;
		Path archive = temporary.resolve("ar.war");
		Files.writeString(archive, "war");
		assertThrows(BoxClientException.class, () -> client(null, null).install(ArtifactKind.APPLICATION, "ar", "1.0", archive, Duration.ofSeconds(5)));
	}

	@Test
	void whenPosted_thenAnswerCarriesStatusAndBody() {
		ManagerClient client = client(null, null);
		ManagerClient.Answer created = client.post("/users", Map.of("uid", "rmarimon"));
		assertEquals(201, created.status());
		assertEquals("rmarimon", Json.string(Json.object(created.body(), "user"), "uid"));
		ManagerClient.Answer refused = client.post("/users", Map.of("firstName", "x"));
		assertEquals(400, refused.status());
		assertEquals("uid is required", refused.message());
		BoxClientException e = assertThrows(BoxClientException.class, () -> client.postOrFail("/users", Map.of(), "user"));
		assertTrue(e.getMessage().contains("uid is required"));
	}

	@Test
	void whenWaitingForMode_thenReturnsWhenReached() {
		ManagerClient client = client(null, null);
		assertEquals("PROVISION", client.mode());
		plane.mode = "RUN";
		assertEquals("RUN", client.waitForMode("RUN", Duration.ofSeconds(2)));
		assertThrows(BoxClientException.class, () -> client.waitForMode("PROVISION", Duration.ofMillis(100)));
	}

	@Test
	void whenServerIsDown_thenModeIsNull() {
		plane.close();
		assertEquals(null, client(null, null).mode());
	}

}
