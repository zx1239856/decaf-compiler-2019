package decaf.backend.dataflow;

import decaf.backend.opt.Rhs;
import decaf.lowlevel.instr.PseudoInstr;
import decaf.lowlevel.instr.Temp;

import java.util.Set;

/**
 * A program location in a basic block, i.e. instruction with results of liveness analysis.
 */
public class Loc<I extends PseudoInstr> {
    public I instr;
    public Set<Temp> liveIn;
    public Set<Temp> liveOut;

    public Set<TempPair> copyIn;
    public Set<TempPair> copyOut;

    public Set<Rhs> in;
    public Set<Rhs> out;

    public Loc(I instr) {
        this.instr = instr;
    }
}
