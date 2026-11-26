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

		JsonNode selected = call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 200);

		JsonNode secondChance = call("GET", "/games/" + id, null, 200);
		assertEquals(secondChance, selected);
		assertEquals("SECOND_CHANCE", secondChance.get("state").asText());
		assertTrue(List.of(
				json.readTree("[{\"number\":1,\"state\":\"SELECTED\"},{\"number\":2,\"state\":\"UNSELECTED\"},{\"number\":3,\"state\":\"OPENED\",\"prize\":\"LOSE\"}]"),
				json.readTree("[{\"number\":1,\"state\":\"SELECTED\"},{\"number\":2,\"state\":\"OPENED\",\"prize\":\"LOSE\"},{\"number\":3,\"state\":\"UNSELECTED\"}]"))
				.contains(secondChance.get("doors")));

		JsonNode opened = call("PUT", "/games/" + id + "/doors/1", "{\"state\": \"OPENED\"}", 200);

		JsonNode finished = call("GET", "/games/" + id, null, 200);
		assertEquals(finished, opened);
		assertEquals(Set.of("id", "state", "doors", "prize", "switched"), fieldNames(finished));
		assertEquals("FINISHED", finished.get("state").asText());
		assertEquals(finished.get("prize"), finished.get("doors").get(0).get("prize"));
		assertFalse(finished.get("switched").asBoolean());

		String prize = finished.get("prize").asText().toLowerCase();
		assertEquals(json.readTree("{\"3\":{\"stayed-" + prize + "\":1,\"totals-" + prize + "\":1}}"), call("GET", "/stats", null, 200));

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
	void postWorksLikePut() throws Exception {
		String id = call("POST", "/games", "{}", 200).get("id").asText();
		assertEquals("SECOND_CHANCE", call("POST", "/games/" + id + "/doors/1", "{\"state\": \"SELECTED\"}", 200).get("state").asText());
		assertEquals("FINISHED", call("POST", "/games/" + id + "/doors/1", "{\"state\": \"OPENED\"}", 200).get("state").asText());
	}

	@Test
	void defaultsToThreeDoors() throws Exception {
		assertEquals(3, call("POST", "/games", null, 200).get("doors").size());
	}

	@Test
	void rejectsBadDoorCounts() throws Exception {
		call("POST", "/games", "{\"numberOfDoors\": 2}", 400);
		call("POST", "/games", "{\"numberOfDoors\": 11}", 400);
		assertEquals(10, call("POST", "/games", "{\"numberOfDoors\": 10}", 200).get("doors").size());
	}

	@Test
	void manyDoorsOpensAllButOne() throws Exception {
		for (int i = 0; i < 20; i++) {
			String id = call("POST", "/games", "{\"numberOfDoors\": 10}", 200).get("id").asText();
			call("PUT", "/games/" + id + "/doors/4", "{\"state\": \"SELECTED\"}", 200);

			JsonNode doors = call("GET", "/games/" + id, null, 200).get("doors");
			List<Integer> closed = new java.util.ArrayList<>();
			for (JsonNode door : doors) {
				if (door.get("state").asText().equals("OPENED")) {
					assertEquals("LOSE", door.get("prize").asText());
				} else {
					closed.add(door.get("number").asInt());
				}
			}
			assertEquals(2, closed.size());
			assertTrue(closed.contains(4));

			int other = closed.get(0) == 4 ? closed.get(1) : closed.get(0);
			call("PUT", "/games/" + id + "/doors/" + other, "{\"state\": \"OPENED\"}", 200);
			assertTrue(call("GET", "/games/" + id, null, 200).get("switched").asBoolean());
		}
		JsonNode stats = call("GET", "/stats", null, 200);
		assertEquals(Set.of("10"), fieldNames(stats));
		assertEquals(20, stats.get("10").path("totals-win").asInt() + stats.get("10").path("totals-lose").asInt());
	}

	@Test
	void oldStatsCountAsThreeDoors() throws Exception {
		store.incrementStats("stayed-win", "totals-win");
		store.incrementStats("3-stayed-win", "3-totals-win");
		store.incrementStats("5-switched-lose", "5-totals-lose");
		assertEquals(
				json.readTree("{\"3\":{\"stayed-win\":2,\"totals-win\":2},\"5\":{\"switched-lose\":1,\"totals-lose\":1}}"),
				call("GET", "/stats", null, 200));
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
	void healthcheck() throws Exception {
		call("POST", "/games", "{}", 200);
		JsonNode body = call("GET", "/healthcheck", null, 200);
		assertEquals("ok", body.get("status").asText());
		assertEquals("DynamoDB", body.get("checks").get(0).get("name").asText());
		assertTrue(body.get("checks").get(0).get("ok").asBoolean());
		assertEquals(json.readTree("{}"), call("GET", "/stats", null, 200));
	}

	@Test
	void healthcheckSaysWhenDynamoIsBroken() throws Exception {
		handler = new Handler(new MontyHallService(new InMemoryStore() {
			@Override
			public java.util.Map<String, Long> getStats() {
				throw new IllegalStateException("DynamoDB is down");
			}
		}));
		JsonNode body = call("GET", "/healthcheck", null, 503);
		assertEquals("fail", body.get("status").asText());
		assertEquals("DynamoDB is down", body.get("checks").get(0).get("detail").asText());
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
		// CORS preflights (204) have no headers, API Gateway adds them
		if (response.getStatusCode() != 204) {
			assertEquals("noindex", response.getHeaders().get("X-Robots-Tag"));
		}
		return response.getBody() == null ? null : json.readTree(response.getBody());
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new java.util.HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
