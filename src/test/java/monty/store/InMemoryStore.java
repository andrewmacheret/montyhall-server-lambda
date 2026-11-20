package monty.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import monty.entities.Game;

/** Stores games as JSON, like DynamoStore, so tests can't share Game objects by accident. */
public class InMemoryStore implements Store {
	private final ObjectMapper json = new ObjectMapper();
	private final Map<String, String> games = new HashMap<>();
	private final Map<String, Long> stats = new TreeMap<>();

	@Override
	public void createGame(Game game) {
		game.setVersion(1);
		games.put(game.getId(), write(game));
	}

	@Override
	public Optional<Game> getGame(String id) {
		String stored = games.get(id);
		if (stored == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(json.readValue(stored, Game.class));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void saveGame(Game game) {
		Game current = getGame(game.getId()).orElseThrow();
		if (current.getVersion() != game.getVersion()) {
			throw new ConflictException("Game was changed by another request: " + game.getId());
		}
		game.setVersion(game.getVersion() + 1);
		games.put(game.getId(), write(game));
	}

	@Override
	public boolean deleteGame(String id) {
		return games.remove(id) != null;
	}

	@Override
	public void incrementStats(String... statNames) {
		for (String statName : statNames) {
			stats.merge(statName, 1L, Long::sum);
		}
	}

	@Override
	public Map<String, Long> getStats() {
		return new TreeMap<>(stats);
	}

	private String write(Game game) {
		try {
			return json.writeValueAsString(game);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}
}
