package decaf.backend.opt;

import decaf.backend.dataflow.CFGBuilder;
import decaf.backend.dataflow.LivenessAnalyzer;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacProg;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;

public class LivenessOpt implements Consumer<TacProg> {
    public void accept(TacProg input) {
        var analyzer = new LivenessAnalyzer<TacInstr>();
        for (var func : input.funcs) {
            var builder = new CFGBuilder<TacInstr>();
            var cfg = builder.buildFrom(func.getInstrSeq());
            analyzer.accept(cfg);
            // iterate over basic blocks
            var instSeq = new ArrayList<TacInstr>();
            instSeq.add(func.getInstrSeq().get(0));
            for (var bb : cfg.nodes) {
                bb.label.ifPresent(e -> instSeq.add(new TacInstr.Mark(e)));
                for (var inst : bb.locs) {
                    boolean optimizedOut = false;
                    switch (inst.instr.type) {
                        case DIRECT_CALL -> {
                            var call = (TacInstr.DirectCall) inst.instr;
                            var dst = call.getWritten();
                            if (dst.size() > 0 && !inst.liveOut.contains(dst.get(0)))
                                call.dst = Optional.empty();
                        }
                        case INDIRECT_CALL -> {
                            var call = (TacInstr.IndirectCall) inst.instr;
                            var dst = call.getWritten();
                            if (dst.size() > 0 && !inst.liveOut.contains(dst.get(0)))
                                call.dst = Optional.empty();
                        }
                        default -> {
                            var dst = inst.instr.getWritten();
                            if (dst.size() > 0 && !inst.liveOut.contains(dst.get(0)))
                                optimizedOut = true;
                        }
                    }
                    if (!optimizedOut)
                        instSeq.add(inst.instr);
                }
            }
            func.setInstrSeq(instSeq);
        }
    }
}
