public class WebGameTest {
    public static void main(String[] args) {
        testBrowserPlayerSuppliesOnePendingGuess();
        testGameStartIsIdempotent();
        testPlayTurnUsesBrowserPlayerGuess();
        testThreeWrongGuessesEndGame();
        System.out.println("WebGameTest passed");
    }

    private static void testBrowserPlayerSuppliesOnePendingGuess() {
        BrowserPlayer player = new BrowserPlayer("Web Player", 5);
        Position expected = new Position(2, 4);
        player.setNextGuess(expected);

        Position actual = player.makeGuess();
        if (actual != expected || actual.getRow() != 2 || actual.getColumn() != 4) {
            throw new AssertionError("BrowserPlayer returned the wrong guess");
        }

        try {
            player.makeGuess();
            throw new AssertionError("Expected an error when no browser guess is pending");
        } catch (IllegalStateException expectedError) {
            // Expected: the previous guess was consumed.
        }
    }

    private static void testGameStartIsIdempotent() {
        BrowserPlayer player = new BrowserPlayer("Web Player", 4);
        MeowdokuGame game = new MeowdokuGame(player, 4);

        Position firstReveal = game.start();
        Position secondReveal = game.start();

        if (firstReveal.getRow() != secondReveal.getRow()
                || firstReveal.getColumn() != secondReveal.getColumn()) {
            throw new AssertionError("start() should return the same revealed cat");
        }
        if (player.getCatsFound() != 1 || player.getGuesses() != 0 || player.getScore() != 0) {
            throw new AssertionError("The free reveal should count once without score or guesses");
        }
    }

    private static void testPlayTurnUsesBrowserPlayerGuess() {
        BrowserPlayer player = new BrowserPlayer("Web Player", 4);
        MeowdokuGame game = new MeowdokuGame(player, 4);
        Position revealed = game.start();
        player.setNextGuess(revealed);

        GuessResult result = game.playTurn();

        if (result != GuessResult.ALREADY_GUESSED) {
            throw new AssertionError("Expected the revealed cat to be already guessed");
        }
        if (player.getGuesses() != 1) {
            throw new AssertionError("playTurn() should record exactly one guess");
        }

        SequentialPlayer consolePlayer = new SequentialPlayer("Console Player", 4);
        MeowdokuGame consoleGame = new MeowdokuGame(consolePlayer, 4);
        consoleGame.start();
        consoleGame.playTurn();
        if (consolePlayer.getGuesses() != 1) {
            throw new AssertionError("Console players should also use playTurn()");
        }
    }

    private static void testThreeWrongGuessesEndGame() {
        BrowserPlayer player = new BrowserPlayer("Web Player", 4);
        MeowdokuGame game = new MeowdokuGame(player, 4);
        game.start();

        int wrongGuesses = 0;
        for (int row = 0; row < 4 && wrongGuesses < 3; row++) {
            for (int column = 0; column < 4 && wrongGuesses < 3; column++) {
                player.setNextGuess(new Position(row, column));
                if (game.playTurn() == GuessResult.WRONG) wrongGuesses++;
            }
        }

        if (wrongGuesses != 3 || player.getLivesRemaining() != 0) {
            throw new AssertionError("Three wrong guesses should use all three lives");
        }
        if (!game.isLost() || !game.isOver() || game.isComplete()) {
            throw new AssertionError("The third wrong guess should end the game as a loss");
        }

        player.setNextGuess(new Position(3, 3));
        try {
            game.playTurn();
            throw new AssertionError("A finished game should reject further guesses");
        } catch (IllegalStateException expected) {
            // Expected: no turns are accepted after the game ends.
        }
    }
}
