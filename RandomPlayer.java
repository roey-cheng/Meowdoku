/**
 * Author: Ruyi Cheng
 * Purpose: Generates random board guesses using a reproducible random seed.
 */
import java.util.*;

class RandomPlayer extends Player{
    private Random random;
    public RandomPlayer(String name, int size, int seedValue){
        super(name,size);
        random = new Random(seedValue);
    }
    public Position makeGuess(){
        int row = random.nextInt(size);
        int col = random.nextInt(size);
        return new Position(row,col);
    }
}
