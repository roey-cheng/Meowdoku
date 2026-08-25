/**
 * Author: Ruyi Cheng
 * Purpose: Runs the game loop, reports guess results, and prints final statistics.
 */
import java.util.*;

class MeowdokuGame{
    private int numberOfCats;
    private GameBoard board;
    private Player player;
    public MeowdokuGame(Player player, int size){
        this.player=player;
        this.board = new GameBoard(size);
        numberOfCats = size;
    }
    
    public void play(){
        Scanner sc = new Scanner(System.in);
        while(player.allCatsFound(numberOfCats)==false){
            System.out.println(board);
            Position playerGuess = player.makeGuess();
            GuessResult result = board.checkGuess(playerGuess);
            player.recordGuess(result);
            System.out.printf(result.getMessage()+"\n");
            System.out.printf("Score: %d\n",player.getScore());
        }
        System.out.println("Congratulations!");
        player.printStatistics();
    }
}
