/**
 * Author: Ruyi Cheng
 * Purpose: Creates the coloured game board, stores cat locations, and checks guesses.
 */
import java.util.*;

class GameBoard{
    private int size;
    private Cell[][] board;
    private int[] solution;
    private Random random=new Random(30);

    static List<Integer> createAvailableColumns(int size) {
        List<Integer> availableColumns = new ArrayList<>();
        for (int column = 0; column < size; column++) {
            availableColumns.add(column);
        }
        return availableColumns;
    }

    static int[] generateSolution(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Board size must be positive");
        }
        int[] generatedSolution = new int[size];
        Arrays.fill(generatedSolution, -1);
        List<Integer> availableColumns = createAvailableColumns(size);

        if (!placeCat(0, generatedSolution, availableColumns)) {
            throw new IllegalArgumentException("No valid solution for board size " + size);
        }
        return generatedSolution;
    }

    private static boolean placeCat(int row, int[] generatedSolution,
                                    List<Integer> availableColumns) {
        if (row == generatedSolution.length) {
            return true;
        }

        List<Integer> candidates = new ArrayList<>(availableColumns);
        Collections.shuffle(candidates);

        for (int column : candidates) {
            if (row == 0 || Math.abs(column - generatedSolution[row - 1]) > 1) {
                generatedSolution[row] = column;
                availableColumns.remove(Integer.valueOf(column));

                if (placeCat(row + 1, generatedSolution, availableColumns)) {
                    return true;
                }

                availableColumns.add(column);
                generatedSolution[row] = -1;
            }
        }
        return false;
    }
    
    public GameBoard(int size){this.size=size;
        solution = generateSolution(size);
        board = new Cell[size][size];
        initialiseBoard();
    }
    
    private void placeInitialColours(){
        for(int i=0;i<size;i++){
            board[i][solution[i]] = new Cell(i);
        }
    }
    
    private void expandRegion(int row, int column, int regionId){
        for(int r=row-1;r<=row+1;r++){
            for(int c=column-1;c<=column+1;c++){
                if((r>=0&&r<size)&&(c>=0&&c<size)){
                    if(board[r][c]==null)board[r][c] = new Cell(regionId);
                }
            }
        }
    }

    public void fillUnassignedCells(){
        List<Position> startingPositions = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                if (board[row][column] != null) {
                    startingPositions.add(new Position(row, column));
                }
            }
        }
        Collections.shuffle(startingPositions, random);

        Queue<Position> queue = new ArrayDeque<>(startingPositions);
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        while (!queue.isEmpty()) {
            Position current = queue.remove();
            int row = current.getRow();
            int column = current.getColumn();
            int regionId = board[row][column].getRegionId();

            for (int[] direction : directions) {
                int nextRow = row + direction[0];
                int nextColumn = column + direction[1];

                if (nextRow >= 0 && nextRow < size
                        && nextColumn >= 0 && nextColumn < size
                        && board[nextRow][nextColumn] == null) {
                    board[nextRow][nextColumn] = new Cell(regionId);
                    queue.add(new Position(nextRow, nextColumn));
                }
            }
        }
    }
    private void initialiseBoard(){
        placeInitialColours();
        for(int row=0;row<size;row++)expandRegion(row, solution[row], row);
        fillUnassignedCells();
    }

    public Position revealRandomCat() {
        int row = random.nextInt(size);
        int column = solution[row];
        board[row][column].setState(CellState.FOUND_CAT);
        return new Position(row, column);
    }
    
    public GuessResult checkGuess(Position position){
        int row = position.getRow();
        int column = position.getColumn();
        Cell cellToCheck = board[row][column];
        if(cellToCheck.getState()!=CellState.HIDDEN){return GuessResult.ALREADY_GUESSED;
        }else if(solution[row]==column){
                cellToCheck.setState(CellState.FOUND_CAT);
                return GuessResult.CORRECT;
        }else{cellToCheck.setState(CellState.WRONG_GUESS);
            return GuessResult.WRONG;
        }
    }
    
    public String toString(){
        StringBuilder s = new StringBuilder();
        for(int row=0;row<size;row++){
            for(int col=0;col<size;col++){
                s.append(board[row][col].toString());
            }
            if(row!=size-1)s.append("\n");
        }
        
        return s.toString();
    }
    
}
