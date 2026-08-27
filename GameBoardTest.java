import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class GameBoardTest {
    public static void main(String[] args) {
        testCellStoresRegionId();
        testAvailableColumns();
        testGeneratedSolutions();
        testBoardRejectsUnsupportedSizes();
        testBoardsHaveNoUnassignedCells();
        testBoardSizeInputRejectsInvalidValues();
        testHumanPlayerRejectsInvalidPositions();
        testBoardRejectsInvalidGuessPositions();
        testRevealedCatCountsAsAFreeHint();
        System.out.println("GameBoardTest passed");
    }

    private static void testCellStoresRegionId() {
        Cell cell = new Cell(5);

        if (cell.getRegionId() != 5) {
            throw new AssertionError("Expected region ID 5");
        }
        if (!cell.toString().equals("5")) {
            throw new AssertionError("Expected hidden cell to display region ID 5");
        }
    }

    private static void testAvailableColumns() {
        List<Integer> columns = GameBoard.createAvailableColumns(6);

        if (!columns.equals(Arrays.asList(0, 1, 2, 3, 4, 5))) {
            throw new AssertionError("Expected columns [0, 1, 2, 3, 4, 5], but got " + columns);
        }
    }

    private static void testGeneratedSolutions() {
        for (int size = 4; size <= 10; size++) {
            int[] solution = GameBoard.generateSolution(size);
            assertValidSolution(solution, size);
        }
    }

    private static void testBoardRejectsUnsupportedSizes() {
        assertInvalidBoardSize(3);
        assertInvalidBoardSize(10);
    }

    private static void assertInvalidBoardSize(int size) {
        try {
            new GameBoard(size);
            throw new AssertionError("Expected board size " + size + " to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: GameBoard owns its supported size range.
        }
    }

    private static void testBoardsHaveNoUnassignedCells() {
        for (int size = 4; size < 10; size++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                GameBoard board = new GameBoard(size);
                String[] rows = board.toString().split("\\R");

                if (rows.length != size) {
                    throw new AssertionError("Expected " + size + " board rows");
                }
                for (String row : rows) {
                    if (row.length() != size) {
                        throw new AssertionError("Board contains an unassigned cell at size " + size);
                    }
                }
            }
        }
    }

    private static void testBoardSizeInputRejectsInvalidValues() {
        Scanner scanner = new Scanner("abc\n3\n10\n4 5\n6\n");
        int size = Main.getValidBoardSize(scanner);

        if (size != 6) {
            throw new AssertionError("Expected board size 6, but got " + size);
        }
    }

    private static void testHumanPlayerRejectsInvalidPositions() {
        Scanner scanner = new Scanner("cat\n-1\n2\n8\n3\n");
        HumanPlayer player = new HumanPlayer("Player 1", 4, scanner);
        Position guess = player.makeGuess();

        if (guess.getRow() != 2 || guess.getColumn() != 3) {
            throw new AssertionError("Expected guess (2, 3), but got " + guess);
        }
    }

    private static void testBoardRejectsInvalidGuessPositions() {
        GameBoard board = new GameBoard(4);
        assertInvalidGuess(board, new Position(-1, 0));
        assertInvalidGuess(board, new Position(4, 0));
        assertInvalidGuess(board, new Position(0, -1));
        assertInvalidGuess(board, new Position(0, 4));
    }

    private static void assertInvalidGuess(GameBoard board, Position position) {
        try {
            board.checkGuess(position);
            throw new AssertionError("Expected invalid guess " + position + " to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: GameBoard owns coordinate validation.
        }
    }

    private static void testRevealedCatCountsAsAFreeHint() {
        int size = 4;
        GameBoard board = new GameBoard(size);
        Position revealed = board.revealRandomCat();
        String[] rows = board.toString().split("\\R");

        if (rows[revealed.getRow()].charAt(revealed.getColumn()) != 'C') {
            throw new AssertionError("Expected the revealed cat to display C");
        }
        if (board.checkGuess(revealed) != GuessResult.ALREADY_GUESSED) {
            throw new AssertionError("Expected the revealed cat to be already found");
        }

        int remainingCats = 0;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                if (board.checkGuess(new Position(row, column)) == GuessResult.CORRECT) {
                    remainingCats++;
                }
            }
        }
        if (remainingCats != size - 1) {
            throw new AssertionError("Expected " + (size - 1) + " cats left to find");
        }

        Player player = new SequentialPlayer("Player 1", size);
        player.recordRevealedCat();
        for (int cat = 1; cat < size; cat++) {
            player.recordGuess(GuessResult.CORRECT);
        }
        if (!player.allCatsFound(size)) {
            throw new AssertionError("Expected the free hint to count as a found cat");
        }
        if (player.getScore() != (size - 1) * GuessResult.CORRECT.getScore()) {
            throw new AssertionError("The free hint should not add to the score");
        }
    }

    private static void assertValidSolution(int[] solution, int size) {
        if (solution.length != size) {
            throw new AssertionError("Expected solution length " + size);
        }

        boolean[] usedColumns = new boolean[size];
        for (int row = 0; row < size; row++) {
            int column = solution[row];
            if (column < 0 || column >= size) {
                throw new AssertionError("Column out of range: " + column);
            }
            if (usedColumns[column]) {
                throw new AssertionError("Column used twice: " + column);
            }
            usedColumns[column] = true;

            if (row > 0 && Math.abs(column - solution[row - 1]) <= 1) {
                throw new AssertionError("Cats touch between rows " + (row - 1) + " and " + row);
            }
        }
    }
}
