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
		if (doorMatch.matches() && method.equals("PUT")) {
			return changeDoor(doorMatch.group(1), Integer.parseInt(doorMatch.group(2)), body.path("state").asText(null));
		}
		if (path.equals("/stats") && method.equals("GET")) {
			return montyHallService.getStats();
		}
		throw new NotFoundException("Not found: " + method + " " + path);
	}

	private Object changeDoor(String gameId, int doorNumber, String state) {
		if (state == null) {
			throw new IllegalArgumentException("state must be specified");
		}
		switch (Door.State.valueOf(state)) {
		case SELECTED:
			montyHallService.selectDoor(gameId, doorNumber);
			break;
		case OPENED:
			montyHallService.chooseDoor(gameId, doorNumber);
			break;
		default:
			throw new IllegalArgumentException("Unsupported state: " + state);
		}
		return Map.of("updated", true);
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
		try {
			return APIGatewayV2HTTPResponse.builder()
					.withStatusCode(status)
					.withHeaders(Map.of("Content-Type", "application/json"))
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
