import java.util.Arrays;
import java.util.List;

public class GameBoardTest {
    public static void main(String[] args) {
        testAvailableColumns();
        testGeneratedSolutions();
        System.out.println("GameBoardTest passed");
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
