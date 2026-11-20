package monty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import monty.entities.Game;
import monty.services.MontyHallService;
import monty.store.ConflictException;
import monty.store.InMemoryStore;

class HandlerTest {
	private final ObjectMapper json = new ObjectMapper();
	private InMemoryStore store;
	private Handler handler;

	@BeforeEach
	void setUp() {
		store = new InMemoryStore();
		handler = new Handler(new MontyHallService(store));
	}

	@Test
	void root() throws Exception {
		JsonNode body = call("GET", "/", null, 200);
		assertEquals(json.readTree("[\"/games\",\"/stats\"]"), body.get("apis"));
		assertTrue(body.get("version").isTextual());
	}

	@Test
	void stayGame() throws Exception {
		JsonNode game = call("POST", "/games", "{\"numberOfDoors\": 3}", 200);
		assertEquals(Set.of("id", "state", "doors"), fieldNames(game));
		assertEquals("SELECT_A_DOOR", game.get("state").asText());
		assertEquals(json.readTree(
				"[{\"number\":1,\"state\":\"UNSELECTED\"},{\"number\":2,\"state\":\"UNSELECTED\"},{\"number\":3,\"state\":\"UNSELECTED\"}]"),
				game.get("doors"));
		String id = game.get("id").asText();
		assertTrue(id.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));

		assertEquals(json.readTree("{\"updated\":true}"), call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 200));

		JsonNode secondChance = call("GET", "/games/" + id, null, 200);
		assertEquals("SECOND_CHANCE", secondChance.get("state").asText());
		assertTrue(List.of(
				json.readTree("[{\"number\":1,\"state\":\"SELECTED\"},{\"number\":2,\"state\":\"UNSELECTED\"},{\"number\":3,\"state\":\"OPENED\",\"prize\":\"LOSE\"}]"),
				json.readTree("[{\"number\":1,\"state\":\"SELECTED\"},{\"number\":2,\"state\":\"OPENED\",\"prize\":\"LOSE\"},{\"number\":3,\"state\":\"UNSELECTED\"}]"))
				.contains(secondChance.get("doors")));

		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"OPENED\"}", 200);

		JsonNode finished = call("GET", "/games/" + id, null, 200);
		assertEquals(Set.of("id", "state", "doors", "prize", "switched"), fieldNames(finished));
		assertEquals("FINISHED", finished.get("state").asText());
		assertEquals(finished.get("prize"), finished.get("doors").get(0).get("prize"));
		assertFalse(finished.get("switched").asBoolean());

		String prize = finished.get("prize").asText().toLowerCase();
		assertEquals(json.readTree("{\"stayed-" + prize + "\":1,\"totals-" + prize + "\":1}"), call("GET", "/stats", null, 200));

		assertEquals(json.readTree("{\"deleted\":true}"), call("DELETE", "/games/" + id, null, 200));
		assertEquals(json.readTree("{\"deleted\":false}"), call("DELETE", "/games/" + id, null, 200));
	}

	@Test
	void switchGame() throws Exception {
		String id = call("POST", "/games", "{}", 200).get("id").asText();
		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 200);

		JsonNode secondChance = call("GET", "/games/" + id, null, 200);
		int otherDoor = secondChance.get("doors").get(1).get("state").asText().equals("UNSELECTED") ? 2 : 3;
		call("PUT", "/games/" + id + "/doors/" + otherDoor, "{\"state\": \"OPENED\"}", 200);

		JsonNode finished = call("GET", "/games/" + id, null, 200);
		assertEquals("FINISHED", finished.get("state").asText());
		assertTrue(finished.get("switched").asBoolean());
		assertEquals(finished.get("prize"), finished.get("doors").get(otherDoor - 1).get("prize"));
		assertEquals("SELECTED", finished.get("doors").get(0).get("state").asText());
		assertFalse(finished.get("doors").get(0).has("prize"));
	}

	@Test
	void defaultsToThreeDoors() throws Exception {
		assertEquals(3, call("POST", "/games", null, 200).get("doors").size());
	}

	@Test
	void rejectsBadDoorCounts() throws Exception {
		call("POST", "/games", "{\"numberOfDoors\": 2}", 400);
		call("POST", "/games", "{\"numberOfDoors\": 101}", 400);
		assertEquals(5, call("POST", "/games", "{\"numberOfDoors\": 5}", 200).get("doors").size());
	}

	@Test
	void errors() throws Exception {
		assertEquals("Game not found: nope", call("GET", "/games/nope", null, 404).get("error").asText());
		call("GET", "/nope", null, 404);
		call("POST", "/games", "not json", 400);

		String id = call("POST", "/games", "{}", 200).get("id").asText();
		call("PUT", "/games/" + id + "/doors/9", "{\"state\": \"SELECTED\"}", 404);
		call("PUT", "/games/" + id + "/doors/1", "{}", 400);
		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"BANANA\"}", 400);
		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"OPENED\"}", 409);

		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 200);
		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 409);
		JsonNode doors = call("GET", "/games/" + id, null, 200).get("doors");
		int openedDoor = doors.get(1).get("state").asText().equals("OPENED") ? 2 : 3;
		assertEquals("Door already opened: " + openedDoor,
				call("PUT", "/games/" + id + "/doors/" + openedDoor, "{\"state\": \"OPENED\"}", 400).get("error").asText());
	}

	@Test
	void corsPreflight() throws Exception {
		call("OPTIONS", "/games", null, 204);
		call("OPTIONS", "/games/anything/doors/1", null, 204);
	}

	@Test
	void doubleClickOnlyCountsOnce() throws Exception {
		String id = call("POST", "/games", "{}", 200).get("id").asText();
		call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 200);

		// two requests read the game before either one saves
		Game first = store.getGame(id).orElseThrow();
		Game second = store.getGame(id).orElseThrow();
		first.setState(Game.State.FINISHED);
		store.saveGame(first);
		try {
			store.saveGame(second);
			throw new AssertionError("expected a conflict");
		} catch (ConflictException expected) {
		}
	}

	private JsonNode call(String method, String path, String body, int expectedStatus) throws Exception {
		APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
				.withRawPath(path)
				.withBody(body)
				.withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
						.withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(method).withPath(path).build())
						.build())
				.build();
		APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);
		assertEquals(expectedStatus, response.getStatusCode(), response.getBody());
		return response.getBody() == null ? null : json.readTree(response.getBody());
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new java.util.HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
