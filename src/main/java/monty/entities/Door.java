package monty.entities;

public class Door {
	public enum State {
		UNSELECTED, SELECTED, OPENED
	};
	public enum Prize {
		LOSE, WIN
	};

	private int doorNumber;
	private State state;
	private Prize prize;

	public Door() {
	}

	public Door(int doorNumber, Prize prize) {
		this.doorNumber = doorNumber;
		this.state = State.UNSELECTED;
		this.prize = prize;
	}

	public int getDoorNumber() {
		return doorNumber;
	}

	public void setDoorNumber(int doorNumber) {
		this.doorNumber = doorNumber;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}

	public Prize getPrize() {
		return prize;
	}

	public void setPrize(Prize prize) {
		this.prize = prize;
	}
}
