/**
 * Author: Ruyi Cheng
 * Purpose: Prompts a human player for valid row and column guesses.
 */
import java.util.*;

class HumanPlayer extends Player{
    private Scanner scanner;
    public HumanPlayer(String name, int size){super(name,size);
        scanner = new Scanner(System.in);
    }
    
    public Position makeGuess(){
        int row = getValidPosition("Enter Row: ",size);
        int col = getValidPosition("Enter Column: ",size);
        return new Position(row,col);
    }
    
    private int getValidPosition(String prompt, int size){
        System.out.print(prompt);
        int input= scanner.nextInt();
        while(input<0||input>=size){
            System.out.print(prompt);
            input= scanner.nextInt();
        }
        return input;
    }
}
