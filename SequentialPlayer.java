/**
 * Author: Ruyi Cheng
 * Purpose: Generates guesses in row-by-row order across the board.
 */
class SequentialPlayer extends Player{
    private int nextPosition=0;
    public SequentialPlayer(String name, int size){super(name,size);}
    public Position makeGuess(){
        int row = nextPosition / size;
        int column = nextPosition % size;
        if(row==size-1&&column==size-1){nextPosition=0;}else{nextPosition++;}
        return new Position(row, column);
    }
}
