package decaf.backend.dataflow;

import decaf.lowlevel.instr.Temp;

public class TempPair implements Comparable<TempPair> {
    public final Temp dst;
    public final Temp src;

    public TempPair(Temp dst, Temp src) {
        this.dst = dst;
        this.src = src;
    }

    public boolean contains(Temp t) {
        return this.dst.compareTo(t) == 0 || this.src.compareTo(t) == 0;
    }

    @Override
    public int compareTo(TempPair that) {
        int res = this.dst.compareTo(that.dst);
        if(res != 0)
            return res;
        else
            return this.src.compareTo(that.src);
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this)
            return true;
        if(!(obj instanceof TempPair))
            return false;
        TempPair o = (TempPair) obj;
        return o.compareTo(this) == 0;
    }

    @Override
    public int hashCode() {
        return dst.hashCode() * 19260817 + src.hashCode();
    }
}
