public class WebGameTest {
    public static void main(String[] args) {
        testBrowserPlayerSuppliesOnePendingGuess();
        testGameStartIsIdempotent();
        testPlayTurnUsesBrowserPlayerGuess();
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
}
