/**
 * Author: Ruyi Cheng
 * Purpose: Prompts a human player for valid row and column guesses.
 */
import java.util.*;

class HumanPlayer extends Player{
    private Scanner scanner;
    public HumanPlayer(String name, int size){
        this(name, size, new Scanner(System.in));
    }

    public HumanPlayer(String name, int size, Scanner scanner){
        super(name,size);
        this.scanner = scanner;
    }
    
    public Position makeGuess(){
        int row = getValidPosition("Enter Row: ",size);
        int col = getValidPosition("Enter Column: ",size);
        return new Position(row,col);
    }
    
    private int getValidPosition(String prompt, int size){
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();

            try {
                int position = Integer.parseInt(input);
                if (position >= 0 && position < size) {
                    return position;
                }
                System.out.printf("Position must be between 0 and %d.%n", size - 1);
            } catch (NumberFormatException e) {
                System.out.println("Please enter one whole number.");
            }
        }
    }
}
