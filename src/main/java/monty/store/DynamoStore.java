package monty.store;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import monty.entities.Game;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/**
 * One DynamoDB table with a string key "pk".
 * Games are stored as "game#<id>" and expire a day after their last change.
 * Stats are counters on a single "stats" item.
 */
public class DynamoStore implements Store {
	private static final Duration GAME_LIFETIME = Duration.ofDays(1);
	private static final String STATS_KEY = "stats";

	private final DynamoDbClient dynamo;
	private final String tableName;
	private final ObjectMapper json = new ObjectMapper();

	public DynamoStore(DynamoDbClient dynamo, String tableName) {
		this.dynamo = dynamo;
		this.tableName = tableName;
	}

	public static DynamoStore fromEnvironment() {
		DynamoDbClient dynamo = DynamoDbClient.builder()
				.region(Region.of(System.getenv("AWS_REGION")))
				// SnapStart has no AWS_ACCESS_KEY_ID env vars, so let the SDK find the credentials
				.credentialsProvider(DefaultCredentialsProvider.builder().build())
				.httpClient(UrlConnectionHttpClient.create())
				.build();
		return new DynamoStore(dynamo, System.getenv("TABLE_NAME"));
	}

	@Override
	public void createGame(Game game) {
		game.setVersion(1);
		dynamo.putItem(r -> r.tableName(tableName)
				.item(toItem(game))
				.conditionExpression("attribute_not_exists(pk)"));
	}

	@Override
	public Optional<Game> getGame(String id) {
		Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(tableName)
				.key(key(gameKey(id)))
				.consistentRead(true)).item();
		if (item == null || item.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(json.readValue(item.get("data").s(), Game.class));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Stored game is not valid JSON: " + id, e);
		}
	}

	@Override
	public void saveGame(Game game) {
		long previousVersion = game.getVersion();
		game.setVersion(previousVersion + 1);
		try {
			dynamo.putItem(r -> r.tableName(tableName)
					.item(toItem(game))
					.conditionExpression("version = :previousVersion")
					.expressionAttributeValues(Map.of(":previousVersion", number(previousVersion))));
		} catch (ConditionalCheckFailedException e) {
			game.setVersion(previousVersion);
			throw new ConflictException("Game was changed by another request: " + game.getId());
		}
	}

	@Override
	public boolean deleteGame(String id) {
		Map<String, AttributeValue> old = dynamo.deleteItem(r -> r.tableName(tableName)
				.key(key(gameKey(id)))
				.returnValues(ReturnValue.ALL_OLD)).attributes();
		return old != null && !old.isEmpty();
	}

	@Override
	public void incrementStats(String... statNames) {
		Map<String, String> names = new HashMap<>();
		StringBuilder expression = new StringBuilder("ADD ");
		for (int i = 0; i < statNames.length; i++) {
			names.put("#s" + i, statNames[i]);
			expression.append(i > 0 ? ", " : "").append("#s").append(i).append(" :one");
		}
		dynamo.updateItem(r -> r.tableName(tableName)
				.key(key(STATS_KEY))
				.updateExpression(expression.toString())
				.expressionAttributeNames(names)
				.expressionAttributeValues(Map.of(":one", number(1))));
	}

	@Override
	public Map<String, Long> getStats() {
		Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(tableName).key(key(STATS_KEY))).item();
		Map<String, Long> stats = new TreeMap<>();
		if (item != null) {
			item.forEach((name, value) -> {
				if (value.n() != null) {
					stats.put(name, Long.parseLong(value.n()));
				}
			});
		}
		return stats;
	}

	private Map<String, AttributeValue> toItem(Game game) {
		try {
			return Map.of(
					"pk", AttributeValue.fromS(gameKey(game.getId())),
					"data", AttributeValue.fromS(json.writeValueAsString(game)),
					"version", number(game.getVersion()),
					"expiresAt", number(Instant.now().plus(GAME_LIFETIME).getEpochSecond()));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Could not write game as JSON: " + game.getId(), e);
		}
	}

	private static String gameKey(String id) {
		return "game#" + id;
	}

	private static Map<String, AttributeValue> key(String pk) {
		return Map.of("pk", AttributeValue.fromS(pk));
	}

	private static AttributeValue number(long n) {
		return AttributeValue.fromN(Long.toString(n));
	}
}
