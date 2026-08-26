/**
 * Author: Ruyi Cheng
 * Purpose: Runs the game loop, reports guess results, and prints final statistics.
 */
class MeowdokuGame{
    private int numberOfCats;
    private GameBoard board;
    private Player player;
    private Position revealedCat;
    public MeowdokuGame(Player player, int size){
        this.player=player;
        this.board = new GameBoard(size);
        numberOfCats = size;
    }

    public Position start(){
        if(revealedCat==null){
            revealedCat = board.revealRandomCat();
            player.recordRevealedCat();
        }
        return revealedCat;
    }

    public GuessResult playTurn(){
        Position playerGuess = player.makeGuess();
        GuessResult result = board.checkGuess(playerGuess);
        player.recordGuess(result);
        return result;
    }

    public boolean isComplete(){
        return player.allCatsFound(numberOfCats);
    }

    public GameBoard getBoard(){return board;}

    public Player getPlayer(){return player;}
    
    public void play(){
        Position starterCat = start();
        System.out.printf("A cat has been revealed at %s!%n", starterCat);

        while(!isComplete()){
            System.out.println(board);
            GuessResult result = playTurn();
            System.out.printf(result.getMessage()+"\n");
            System.out.printf("Score: %d\n",player.getScore());
        }
        System.out.println("Congratulations!");
        player.printStatistics();
    }
}
