package monty.store;

import java.util.Map;
import java.util.Optional;

import monty.entities.Game;

public interface Store {
	void createGame(Game game);

	Optional<Game> getGame(String id);

	// Throws ConflictException if someone else saved the game since it was read
	void saveGame(Game game);

	boolean deleteGame(String id);

	void incrementStats(String... statNames);

	Map<String, Long> getStats();
}
