/**
 * Author: Ruyi Cheng
 * Purpose: Stores the row and column coordinates of a board position.
 */
class Position{
    private int row;
    private int column;
    
    public Position(int row, int column){this.row=row; this.column=column;}
    
    public int getRow(){return row;}
    public int getColumn(){return column;}
    public String toString(){return String.format("(%d, %d)",row,column);}
    
}
