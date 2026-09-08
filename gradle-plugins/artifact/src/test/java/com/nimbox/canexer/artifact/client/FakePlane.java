package com.nimbox.canexer.artifact.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A manager plane and a tower on one local server, answering what the client
 * needs: status (open, or refusing until a bearer arrives), mode, install
 * with a job that settles after one poll, JSON posts echoed back, and a tower
 * that mints tokens and answers adopt and activate.
 */
final class FakePlane implements AutoCloseable {

	static final String TOKEN = "minted-token";

	private final HttpServer server;
	final List<String> requests = new ArrayList<>();
	final List<String> bodies = new ArrayList<>();
	boolean tokensOnly;
	String mode = "PROVISION";
	int polls;
	boolean serverRestart;

	FakePlane() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		requests.add(exchange.getRequestMethod() + " " + path + (authorization == null ? "" : " " + authorization));
		bodies.add(body);

		if (path.equals("/graphql")) {
			if (!"Bearer login".equals(authorization)) {
				reply(exchange, 401, "{}");
			} else if (body.contains("token(name")) {
				reply(exchange, 200, "{\"data\":{\"tower\":{\"boxes\":{\"token\":{\"result\":{\"token\":\"" + TOKEN + "\"},\"errors\":[]}}}}}");
			} else if (body.contains("adopt(name")) {
				reply(exchange, 200, "{\"data\":{\"tower\":{\"boxes\":{\"adopt\":{\"result\":{\"name\":\"demotwo\"},\"errors\":[]}}}}}");
			} else if (body.contains("activate(name")) {
				reply(exchange, 200, "{\"data\":{\"tower\":{\"boxes\":{\"activate\":{\"result\":{\"token\":\"activation\",\"delivered\":false,\"box\":{\"name\":\"demotwo\",\"country\":\"DO\",\"currency\":\"DOP\",\"locale\":\"es_DO\",\"zone\":\"America/Santo_Domingo\"}},\"errors\":[]}}}}}");
			} else {
				reply(exchange, 200, "{\"data\":{\"tower\":{\"boxes\":{\"other\":{\"result\":null,\"errors\":[{\"name\":\"box\",\"code\":\"unknown\",\"value\":\"nope\"}]}}}}}");
			}
			return;
		}

		if (path.equals("/server/mode")) {
			reply(exchange, 200, "{\"mode\":\"" + mode + "\"}");
			return;
		}

		if (tokensOnly && !("Bearer " + TOKEN).equals(authorization)) {
			reply(exchange, 401, "{\"error\":\"unauthorized\"}");
			return;
		}

		switch (path) {
		case "/server/manager/status" -> reply(exchange, 200, "{\"box\":\"testone\",\"mode\":\"" + mode + "\"}");
		case "/server/manager/install" -> reply(exchange, 202, "{\"job\":{\"id\":\"job-1\",\"state\":\"QUEUED\",\"kind\":\"application\"}}");
		case "/server/manager/jobs/job-1" -> {
			polls++;
			if (serverRestart && polls <= 2) {
				exchange.close();
				return;
			}
			reply(exchange, 200, polls >= 2 ? "{\"id\":\"job-1\",\"state\":\"DONE\"}" : "{\"id\":\"job-1\",\"state\":\"INSTALLING\"}");
		}
		case "/server/manager/users" -> reply(exchange, body.contains("\"uid\":\"rmarimon\"") ? 201 : 400, body.contains("\"uid\":\"rmarimon\"") ? "{\"user\":{\"uid\":\"rmarimon\"}}" : "{\"error\":\"uid is required\"}");
		case "/server/manager/reset" -> reply(exchange, 202, "{\"status\":\"resetting\",\"mode\":\"soft\"}");
		default -> reply(exchange, 404, "{\"error\":\"no such path\"}");
		}
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	static Map<String, Object> json(String text) {
		return Json.object(text);
	}

}
