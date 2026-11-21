package monty.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Game {
	public enum State {
		SELECT_A_DOOR, SECOND_CHANCE, FINISHED
	};

	private String id = UUID.randomUUID().toString();
	private State state = State.SELECT_A_DOOR;
	private boolean switched;
	private Door.Prize prize;
	// bumped on every save so two requests can't change the same game at once
	private long version;
	private List<Door> doors = new ArrayList<>();

	public Game() {
	}

	@JsonIgnore
	public Door getDoorByNumber(int doorNumber) {
		for (Door door : doors) {
			if (door.getDoorNumber() == doorNumber) {
				return door;
			}
		}
		return null;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}

	public boolean isSwitched() {
		return switched;
	}

	public void setSwitched(boolean switched) {
		this.switched = switched;
	}

	public Door.Prize getPrize() {
		return prize;
	}

	public void setPrize(Door.Prize prize) {
		this.prize = prize;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

	public List<Door> getDoors() {
		return doors;
	}

	public void setDoors(List<Door> doors) {
		this.doors = doors;
	}
}
