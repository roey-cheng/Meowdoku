/**
 * Author: Ruyi Cheng
 * Purpose: Represents one board cell and displays its colour or guess result.
 */
class Cell{
    private Colour colour;
    private CellState state=CellState.HIDDEN;
    public Cell(Colour c){colour=c;}
    public CellState getState(){return state;}
    public void setState(CellState state){this.state =state;}
    
    public String toString(){
        if(state==CellState.HIDDEN){return colour.toString().charAt(0)+"";}
        else{return state.getSymbol()+"";
    }}
    
}
