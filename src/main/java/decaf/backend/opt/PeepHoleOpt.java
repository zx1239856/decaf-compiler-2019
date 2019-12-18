package decaf.backend.opt;

import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacProg;

import java.util.ArrayList;
import java.util.function.Consumer;

public class PeepHoleOpt implements Consumer<TacProg> {
    public void accept(TacProg input) {
        for (var func : input.funcs) {
            var seq = func.getInstrSeq();
            var instSeq = new ArrayList<TacInstr>();
            for (int i = 0; i < seq.size(); ++i) {
                var prev = i > 0 ? seq.get(i) : null;
                var curr = seq.get(i);
                var next = i + 1 < seq.size() ? seq.get(i + 1) : null;
                var omit = false;
                switch (curr.type) {
                    case BRANCH -> {
                        var target = ((TacInstr.Branch) curr).target;
                        if (next != null && next.type == TacInstr.TacType.MARK && ((TacInstr.Mark) next).label.equals(target))
                            omit = true;
                    }
                    case ASSIGN -> {
                        var assign = (TacInstr.Assign) curr;
                        omit = assign.src.index == assign.dst.index;
                    }
                    case DIRECT_CALL -> {
                        var call = (TacInstr.DirectCall) curr;
                        if(call.dst.isEmpty()) {
                            if(call.hint == TacInstr.CompilerHint.CONSTRUCTOR)
                                omit = true;
                            else if(call.hint == TacInstr.CompilerHint.ALLOC) {
                                omit = true;
                                assert prev != null && prev.hint == TacInstr.CompilerHint.ALLOC;
                                instSeq.remove(instSeq.size() - 1);
                            }
                        }
                    }
                    default -> omit = false;
                }
                if (!omit)
                    instSeq.add(curr);
            }
            func.setInstrSeq(instSeq);
        }
    }
}
