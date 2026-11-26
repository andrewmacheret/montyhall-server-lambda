package monty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import monty.entities.Door;
import monty.entities.Game;
import monty.services.MontyHallService;
import monty.services.NotFoundException;
import monty.store.ConflictException;
import monty.store.DynamoStore;

/**
 * Entry point for API Gateway (HTTP API, payload format 2.0).
 * CORS is handled by API Gateway, not here.
 */
public class Handler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
	private static final Pattern GAME_PATH = Pattern.compile("^/games/([^/]+)$");
	private static final Pattern DOOR_PATH = Pattern.compile("^/games/([^/]+)/doors/([0-9]+)$");
	private static final String VERSION = loadVersion();

	private final ObjectMapper json = new ObjectMapper();
	private final MontyHallService montyHallService;

	public Handler() {
		this(new MontyHallService(DynamoStore.fromEnvironment()));
		prime();
	}

	public Handler(MontyHallService montyHallService) {
		this.montyHallService = montyHallService;
	}

	@Override
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
		String method = event.getRequestContext().getHttp().getMethod();
		String path = event.getRawPath();
		// The $default route sends CORS preflights here too. API Gateway adds the CORS headers.
		if (method.equals("OPTIONS")) {
			return APIGatewayV2HTTPResponse.builder().withStatusCode(204).build();
		}
		if (path.equals("/healthcheck") && method.equals("GET")) {
			return healthcheck();
		}
		try {
			return response(200, route(method, path, readBody(event)));
		} catch (NotFoundException e) {
			return error(404, e);
		} catch (IllegalArgumentException e) {
			return error(400, e);
		} catch (IllegalStateException | ConflictException e) {
			return error(409, e);
		} catch (Exception e) {
			System.err.println("Unexpected error for " + method + " " + path);
			e.printStackTrace();
			return response(500, Map.of("error", "Internal server error"));
		}
	}

	private Object route(String method, String path, JsonNode body) {
		Matcher doorMatch = DOOR_PATH.matcher(path);
		Matcher gameMatch = GAME_PATH.matcher(path);

		if (path.equals("/") && method.equals("GET")) {
			return Map.of("version", VERSION, "apis", List.of("/games", "/stats"));
		}
		if (path.equals("/games") && method.equals("POST")) {
			JsonNode numberOfDoors = body.path("numberOfDoors");
			return gameJson(montyHallService.newGame(numberOfDoors.isNumber() ? numberOfDoors.asInt() : null));
		}
		if (gameMatch.matches() && method.equals("GET")) {
			return gameJson(montyHallService.getGame(gameMatch.group(1)));
		}
		if (gameMatch.matches() && method.equals("DELETE")) {
			return Map.of("deleted", montyHallService.removeGame(gameMatch.group(1)));
		}
		// POST does the same as PUT. Browsers can send a POST without waiting on a CORS preflight first
		if (doorMatch.matches() && (method.equals("PUT") || method.equals("POST"))) {
			return changeDoor(doorMatch.group(1), Integer.parseInt(doorMatch.group(2)), body.path("state").asText(null));
		}
		if (path.equals("/stats") && method.equals("GET")) {
			return montyHallService.getStats();
		}
		throw new NotFoundException("Not found: " + method + " " + path);
	}

	// GET /healthcheck, for the daily canary (github.com/andrewmacheret/canary).
	// It only reads the stats, so it doesn't count as a game
	private APIGatewayV2HTTPResponse healthcheck() {
		Map<String, Object> check = new LinkedHashMap<>();
		check.put("name", "DynamoDB");
		boolean ok;
		try {
			ok = true;
			check.put("detail", "read stats for " + montyHallService.getStats().size() + " door counts");
		} catch (Exception e) {
			ok = false;
			check.put("detail", String.valueOf(e.getMessage()));
		}
		check.put("ok", ok);
		Map<String, Object> body = Map.of("status", ok ? "ok" : "fail", "checks", List.of(check));
		return response(ok ? 200 : 503, body, Map.of("Cache-Control", "no-store"));
	}

	private Object changeDoor(String gameId, int doorNumber, String state) {
		if (state == null) {
			throw new IllegalArgumentException("state must be specified");
		}
		return switch (Door.State.valueOf(state)) {
		case SELECTED -> gameJson(montyHallService.selectDoor(gameId, doorNumber));
		case OPENED -> gameJson(montyHallService.chooseDoor(gameId, doorNumber));
		default -> throw new IllegalArgumentException("Unsupported state: " + state);
		};
	}

	// SnapStart snapshots the Lambda after the constructor runs, so play a throwaway game first.
	// That loads the AWS SDK and JSON code into the snapshot, instead of on a player's first click
	private void prime() {
		try {
			handleRequest(event("GET", "/stats", null), null);
			String id = json.readTree(handleRequest(event("POST", "/games", "{}"), null).getBody()).get("id").asText();
			handleRequest(event("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}"), null);
			handleRequest(event("GET", "/games/" + id, null), null);
			handleRequest(event("DELETE", "/games/" + id, null), null);
		} catch (Exception e) {
			System.err.println("Priming failed, the first requests will be slower");
			e.printStackTrace();
		}
	}

	private static APIGatewayV2HTTPEvent event(String method, String path, String body) {
		return APIGatewayV2HTTPEvent.builder()
				.withRawPath(path)
				.withBody(body)
				.withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
						.withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(method).withPath(path).build())
						.build())
				.build();
	}

	// convert a game to json, hiding what's behind unopened doors
	private static Map<String, Object> gameJson(Game game) {
		Map<String, Object> gameObject = new LinkedHashMap<>();
		gameObject.put("id", game.getId());
		gameObject.put("state", game.getState().name());
		gameObject.put("doors", game.getDoors().stream().map(Handler::doorJson).toList());
		if (game.getState() == Game.State.FINISHED) {
			gameObject.put("prize", game.getPrize().name());
			gameObject.put("switched", game.isSwitched());
		}
		return gameObject;
	}

	private static Map<String, Object> doorJson(Door door) {
		Map<String, Object> doorObject = new LinkedHashMap<>();
		doorObject.put("number", door.getDoorNumber());
		doorObject.put("state", door.getState().name());
		if (door.getState() == Door.State.OPENED) {
			doorObject.put("prize", door.getPrize().name());
		}
		return doorObject;
	}

	private JsonNode readBody(APIGatewayV2HTTPEvent event) throws IOException {
		String body = event.getBody();
		if (body == null || body.isBlank()) {
			return json.createObjectNode();
		}
		if (event.getIsBase64Encoded()) {
			body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
		}
		try {
			return json.readTree(body);
		} catch (IOException e) {
			throw new IllegalArgumentException("Request body must be JSON");
		}
	}

	private APIGatewayV2HTTPResponse error(int status, Exception e) {
		return response(status, Map.of("error", e.getMessage()));
	}

	private APIGatewayV2HTTPResponse response(int status, Object body) {
		return response(status, body, Map.of());
	}

	private APIGatewayV2HTTPResponse response(int status, Object body, Map<String, String> extraHeaders) {
		Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
		headers.put("Content-Type", "application/json");
		// Keeps Google from listing the API in search results
		headers.put("X-Robots-Tag", "noindex");
		try {
			return APIGatewayV2HTTPResponse.builder()
					.withStatusCode(status)
					.withHeaders(headers)
					.withBody(json.writeValueAsString(body))
					.build();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String loadVersion() {
		try (InputStream in = Handler.class.getResourceAsStream("/version.properties")) {
			Properties properties = new Properties();
			properties.load(in);
			return properties.getProperty("version");
		} catch (IOException | NullPointerException e) {
			return "UNSTABLE";
		}
	}
}
