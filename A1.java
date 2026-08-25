/**
 * Author: Ruyi Cheng
 * Purpose: Defines the game enums and starts a four-by-four Meowdoku game.
 */
enum Colour{BLUE,RED,GREEN,YELLOW;}

enum CellState{HIDDEN('_'),FOUND_CAT('C'),WRONG_GUESS('X');
    private final char symbol;
    private CellState(char c){
        symbol=c;
    }
    public char getSymbol(){return symbol;}
}

enum GuessResult{CORRECT(10,"Correct!"),WRONG(-1,"No cat there!"),ALREADY_GUESSED(0,"Position already guessed!");
    private final int score;
    private final String message;
    
    private GuessResult(int num, String m){
        score=num;message=m;
    }
    public int getScore(){return score;}
    public String getMessage(){return message;}
}

public class A1 {

    public static void main(String[] args) {
        Player player = new SequentialPlayer("Player 1", 4);
        MeowdokuGame game = new MeowdokuGame(player, 4);
        game.play();
    }
}
