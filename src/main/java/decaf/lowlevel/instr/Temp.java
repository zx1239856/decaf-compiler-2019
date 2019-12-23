package decaf.lowlevel.instr;

/**
 * A pseudo register, or a temporary variable.
 * <p>
 * For short, we simply call it temp.
 */
public class Temp implements Comparable<Temp> {
    /**
     * Index, must be unique inside a function.
     */
    public final int index;

    public Temp(int index) {
        this.index = index;
    }

    @Override
    public String toString() {
        return "_T" + index;
    }

    @Override
    public int compareTo(Temp that) {
        return this.index - that.index;
    }

    @Override
    public int hashCode() {
        return Integer.valueOf(index).hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        if(obj instanceof Temp) {
            return this.compareTo((Temp)obj) == 0;
        } else return false;
    }
}