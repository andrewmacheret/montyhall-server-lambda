package monty.services;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import monty.entities.Door;
import monty.entities.Door.Prize;
import monty.entities.Game;
import monty.store.Store;

public class MontyHallService {
	public static final int MIN_DOORS = 3;
	public static final int MAX_DOORS = 10;
	public static final int DEFAULT_DOORS = 3;
	private static final Pattern STAT_NAME = Pattern.compile("^([0-9]+)-(.+)$");

	private final Store store;
	private final RandomGenerator random;

	public MontyHallService(Store store) {
		// SnapStart copies memory to every new Lambda, so a normal RNG would repeat the same games.
		// SecureRandom gets fresh randomness after each copy
		this(store, new SecureRandom());
	}

	public MontyHallService(Store store, RandomGenerator random) {
		this.store = store;
		this.random = random;
	}

	public Game newGame(Integer numberOfDoors) {
		int doorCount = numberOfDoors != null ? numberOfDoors : DEFAULT_DOORS;
		if (doorCount < MIN_DOORS || doorCount > MAX_DOORS) {
			throw new IllegalArgumentException(
					"numberOfDoors must be between " + MIN_DOORS + " and " + MAX_DOORS + ", is: " + doorCount);
		}

		Game game = new Game();
		int winningDoorNumber = random.nextInt(doorCount) + 1;
		for (int doorNumber = 1; doorNumber <= doorCount; doorNumber++) {
			game.getDoors().add(new Door(doorNumber, winningDoorNumber == doorNumber ? Prize.WIN : Prize.LOSE));
		}

		store.createGame(game);
		return game;
	}

	public Game getGame(String gameId) {
		return store.getGame(gameId).orElseThrow(() -> new NotFoundException("Game not found: " + gameId));
	}

	public boolean removeGame(String gameId) {
		return store.deleteGame(gameId);
	}

	public Game selectDoor(String gameId, int doorNumber) {
		Game game = getGame(gameId);
		assertGameState(game, Game.State.SELECT_A_DOOR);

		Door door = getDoor(game, doorNumber);
		door.setState(Door.State.SELECTED);

		// leave one other door closed: the car if the player missed it, or else a random goat.
		// then open the rest, which are all goats
		List<Door> others = game.getDoors().stream().filter(d -> d != door).toList();
		Door keepClosed = others.stream()
				.filter(d -> d.getPrize() == Prize.WIN)
				.findFirst()
				.orElseGet(() -> others.get(random.nextInt(others.size())));
		for (Door other : others) {
			if (other != keepClosed) {
				other.setState(Door.State.OPENED);
			}
		}
		game.setState(Game.State.SECOND_CHANCE);

		store.saveGame(game);
		return game;
	}

	public Game chooseDoor(String gameId, int doorNumber) {
		Game game = getGame(gameId);
		assertGameState(game, Game.State.SECOND_CHANCE);

		Door door = getDoor(game, doorNumber);

		// prevent choosing an already opened door (that's cheating!)
		if (door.getState() == Door.State.OPENED) {
			throw new IllegalArgumentException("Door already opened: " + doorNumber);
		}
		boolean switched = door.getState() != Door.State.SELECTED;

		door.setState(Door.State.OPENED);
		game.setState(Game.State.FINISHED);
		game.setPrize(door.getPrize());
		game.setSwitched(switched);

		store.saveGame(game);

		String prefix = game.getDoors().size() + "-";
		String prize = door.getPrize().name().toLowerCase();
		store.incrementStats(prefix + (switched ? "switched" : "stayed") + "-" + prize, prefix + "totals-" + prize);
		return game;
	}

	// stored as "5-stayed-win" and returned as {"5": {"stayed-win": ...}}
	public Map<Integer, Map<String, Long>> getStats() {
		Map<Integer, Map<String, Long>> stats = new TreeMap<>();
		store.getStats().forEach((name, count) -> {
			Matcher match = STAT_NAME.matcher(name);
			// names without a door count are from before games could have more than 3 doors
			int doors = match.matches() ? Integer.parseInt(match.group(1)) : DEFAULT_DOORS;
			String stat = match.matches() ? match.group(2) : name;
			stats.computeIfAbsent(doors, d -> new TreeMap<>()).merge(stat, count, Long::sum);
		});
		return stats;
	}

	private static void assertGameState(Game game, Game.State state) {
		if (game.getState() != state) {
			throw new IllegalStateException("Game not in the " + state + " state: " + game.getState());
		}
	}

	private static Door getDoor(Game game, int doorNumber) {
		Door door = game.getDoorByNumber(doorNumber);
		if (door == null) {
			throw new NotFoundException("Door not found: " + doorNumber);
		}
		return door;
	}
}
