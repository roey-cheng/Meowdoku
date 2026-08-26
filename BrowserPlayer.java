/**
 * Receives guesses from the browser and exposes them through Player.makeGuess().
 */
class BrowserPlayer extends Player {
    private Position nextGuess;

    public BrowserPlayer(String name, int size) {
        super(name, size);
    }

    public void setNextGuess(Position nextGuess) {
        if (nextGuess == null) {
            throw new IllegalArgumentException("Browser guess cannot be null");
        }
        this.nextGuess = nextGuess;
    }

    public Position makeGuess() {
        if (nextGuess == null) {
            throw new IllegalStateException("No browser guess is pending");
        }

        Position guess = nextGuess;
        nextGuess = null;
        return guess;
    }
}
