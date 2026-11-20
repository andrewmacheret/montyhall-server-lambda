package monty.services;

import java.util.Map;
import java.util.random.RandomGenerator;

import monty.entities.Door;
import monty.entities.Door.Prize;
import monty.entities.Game;
import monty.store.Store;

public class MontyHallService {
	public static final int MIN_DOORS = 3;
	public static final int MAX_DOORS = 100;
	public static final int DEFAULT_DOORS = 3;

	private final Store store;
	private final RandomGenerator random;

	public MontyHallService(Store store) {
		this(store, RandomGenerator.getDefault());
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

	public void selectDoor(String gameId, int doorNumber) {
		Game game = getGame(gameId);
		assertGameState(game, Game.State.SELECT_A_DOOR);

		Door door = getDoor(game, doorNumber);
		door.setState(Door.State.SELECTED);

		// open a random losing door that the player didn't pick
		Door doorToOpen = game.getRandomUnselectedLosingDoor(random);
		if (doorToOpen == null) {
			throw new IllegalStateException("Unable to open a random unselected losing door");
		}
		doorToOpen.setState(Door.State.OPENED);
		game.setState(Game.State.SECOND_CHANCE);

		store.saveGame(game);
	}

	public void chooseDoor(String gameId, int doorNumber) {
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

		String prize = door.getPrize().name().toLowerCase();
		store.incrementStats((switched ? "switched" : "stayed") + "-" + prize, "totals-" + prize);
	}

	public Map<String, Long> getStats() {
		return store.getStats();
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
