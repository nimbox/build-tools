package com.nimbox.canexer.artifact.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TowerClientTest {

	FakePlane plane;
	TowerClient tower;

	@BeforeEach
	void start() throws IOException {
		plane = new FakePlane();
		tower = new TowerClient(plane.url() + "/", "login", "test", HttpClient.newHttpClient());
	}

	@AfterEach
	void stop() {
		plane.close();
	}

	@Test
	void whenTokenAsked_thenMintedForTheBox() {
		assertEquals(FakePlane.TOKEN, tower.token("demotwo", List.of("read", "write")));
		assertTrue(plane.bodies.get(0).contains("\"name\":\"demotwo\""));
		assertTrue(plane.bodies.get(0).contains("\"verbs\":[\"read\",\"write\"]"));
	}

	@Test
	void whenAdopted_thenNameReturned() {
		assertEquals("demotwo", tower.adopt("demotwo", "communicable-banks"));
		assertTrue(plane.bodies.get(0).contains("\"label\":\"communicable-banks\""));
	}

	@Test
	void whenActivated_thenTokenAndFactsReturned() {
		TowerClient.Activation activation = tower.activate("demotwo");
		assertEquals("activation", activation.token());
		assertEquals("DO", activation.country());
		assertEquals("es_DO", activation.locale());
		assertFalse(activation.delivered());
		Map<String, Object> payload = activation.payload();
		assertEquals(List.of("token", "country", "currency", "locale", "zone"), List.copyOf(payload.keySet()));
	}

	@Test
	void whenTowerRefuses_thenErrorsAreTheMessage() {
		BoxClientException e = assertThrows(BoxClientException.class, () -> tower.mutation("other", "mutation { other }", Map.of(), "other failed"));
		assertTrue(e.getMessage().contains("unknown"));
	}

	@Test
	void whenLoginRefused_thenSaysToLogIn() {
		TowerClient stranger = new TowerClient(plane.url(), "wrong", "test", HttpClient.newHttpClient());
		BoxClientException e = assertThrows(BoxClientException.class, () -> stranger.token("demotwo", List.of("read")));
		assertTrue(e.getMessage().contains("tower login"));
	}

}
