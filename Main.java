/**
 * Author: Ruyi Cheng
 * Purpose: Defines the game enums and starts a four-by-four Meowdoku game.
 */
import java.util.Scanner;

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

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int size = getValidBoardSize(scanner);

        Player player = new HumanPlayer("Player 1", size, scanner);
        MeowdokuGame game = new MeowdokuGame(player, size);
        game.play();
    }

    static int getValidBoardSize(Scanner scanner) {
        while (true) {
            System.out.print("Please choose a board size between 4 and 9: ");
            String input = scanner.nextLine().trim();

            try {
                int size = Integer.parseInt(input);
                if (size > 3 && size < 10) {
                    return size;
                }
                System.out.println("Board size must be between 4 and 9.");
            } catch (NumberFormatException e) {
                System.out.println("Please enter one whole number.");
            }
        }
    }
}
