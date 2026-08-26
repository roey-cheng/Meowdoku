/**
 * Author: Ruyi Cheng
 * Purpose: Represents one board cell and displays its id or guess result.
 */
class Cell{
    private int regionId;
    private CellState state=CellState.HIDDEN;
    public Cell(int regionId){this.regionId=regionId;}
    public int getRegionId(){return regionId;}
    public CellState getState(){return state;}
    public void setState(CellState state){this.state =state;}
    
    public String toString(){
        if(state==CellState.HIDDEN){return String.valueOf(regionId);}
        else{return state.getSymbol()+"";
    }}
    
}
