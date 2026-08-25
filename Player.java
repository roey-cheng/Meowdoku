/**
 * Author: Ruyi Cheng
 * Purpose: Tracks a player's guesses, cats found, and score while defining how guesses are made.
 */
abstract class Player{
    private String name;
    private int guesses=0;
    private int catsFound=0;
    private int score=0;
    protected int size;
    
    public Player(String name, int size){this.name=name;this.size=size;}
    public int getScore(){return score;}
    public void recordGuess(GuessResult result){guesses++;
        score += result.getScore();
        if(result==GuessResult.CORRECT){catsFound++;}
    }
    public boolean allCatsFound(int numberOfCats){
        if(numberOfCats==catsFound)return true;
        return false;
    }
    public void printStatistics(){
        System.out.printf("Player: %s\nNumber of guesses: %d\nCats found: %d\nScore: %d\n",name,guesses,catsFound,score);
    }
    public abstract Position makeGuess();
    
    public String toString(){return String.format("%s (Score: %d)",name, score);}
}
