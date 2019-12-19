package decaf.lowlevel.tac;

import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.label.FuncLabel;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class TacFunc implements Comparable<TacFunc> {
    public final FuncLabel entry;

    public TacInstr.CompilerHint hint;

    public final int numArgs;

    TacFunc(FuncLabel entry, int numArgs) {
        this.entry = entry;
        this.numArgs = numArgs;
        this.hint = TacInstr.CompilerHint.NO_HINT;
    }

    public List<TacInstr> getInstrSeq() {
        return instrSeq;
    }

    public void setInstrSeq(List<TacInstr> instr) {
        instrSeq = instr;
    }

    public int getUsedTempCount() {
        return tempUsed;
    }

    public Temp getFreshTemp() {
        return new Temp(tempUsed++);
    }

    List<TacInstr> instrSeq = new ArrayList<>();

    int tempUsed;

    void add(TacInstr instr) {
        instr.hint = hint;
        instrSeq.add(instr);
    }

    public void printTo(PrintWriter pw) {
        for (var instr : instrSeq) {
            if (instr.isLabel()) {
                pw.println(instr);
            } else {
                pw.println("    " + instr);
            }
        }
        pw.println();
    }

    @Override
    public int compareTo(TacFunc that) {
        return this.entry.name.compareTo(that.entry.name);
    }
}
