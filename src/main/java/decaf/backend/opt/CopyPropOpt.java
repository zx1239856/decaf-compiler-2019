package decaf.backend.opt;

import decaf.backend.dataflow.BasicBlock;
import decaf.backend.dataflow.CFG;
import decaf.backend.dataflow.CFGBuilder;
import decaf.backend.dataflow.TempPair;
import decaf.lowlevel.instr.PseudoInstr;
import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacProg;

import java.util.*;
import java.util.function.Consumer;

class CopyAnalyzer implements Consumer<CFG<decaf.lowlevel.tac.TacInstr>> {
    @Override
    public void accept(CFG<TacInstr> graph) {
        for(var bb : graph.nodes) {
            computeGenKill(bb);
            bb.copyOut = new HashSet<>();
            bb.copyOut.addAll(bb.copyGen);
            bb.copyIn = new HashSet<>();
        }

        var changed = true;

        for(var bb : graph.nodes) {
            for(var prev : graph.getPrev(bb.id)) {
                bb.copyIn.addAll(graph.getBlock(prev).copyOut);
                break;
            }
        }

        do {
            changed = false;
            for (var bb : graph.nodes) {
                for (var prev : graph.getPrev(bb.id)) {
                    bb.copyIn.retainAll(graph.getBlock(prev).copyOut);
                }
                var temp = new HashSet<>(bb.copyIn);
                temp.removeIf(v -> bb.copyKill.contains(v.dst) || bb.copyKill.contains(v.src));
                if (bb.copyOut.addAll(temp)) {
                    changed = true;
                }
            }
        } while (changed);

        for(var bb : graph.nodes) {
            analyzeCopyForEachLoc(bb);
        }
    }

    private void analyzeCopyForEachLoc(BasicBlock<TacInstr> bb) {
        var copyIn = new HashSet<>(bb.copyIn);
        var it = bb.iterator();
        while (it.hasNext()) {
            var loc = it.next();
            loc.copyIn = new HashSet<>(copyIn);
            copyIn.removeIf(v -> loc.instr.getWritten().size() > 0 && v.contains(loc.instr.getWritten().get(0)));
            if(loc.instr.type == TacInstr.TacType.ASSIGN) {
                var assign = (TacInstr.Assign)loc.instr;
                copyIn.add(new TempPair(assign.dst, assign.src));
            }
            loc.copyOut = new HashSet<>(copyIn);
        }
    }

    private void computeGenKill(BasicBlock<TacInstr> bb) {
        bb.copyGen = new HashSet<>();
        bb.copyKill = new HashSet<>();
        var it = bb.iterator();
        while (it.hasNext()) {
            var loc = it.next();
            if(loc.instr.type.equals(TacInstr.TacType.ASSIGN)) {
                var assign = (TacInstr.Assign)loc.instr;
                if(assign.src.compareTo(assign.dst) == 0) {
                    it.remove();
                    continue;
                }
            }
            bb.copyKill.addAll(loc.instr.getWritten());
            if(loc.instr.getWritten().size() > 0) {
                bb.copyGen.removeIf(v -> v.contains(loc.instr.getWritten().get(0)));
            }
            if(loc.instr.type.equals(TacInstr.TacType.ASSIGN)) {
                var assign = (TacInstr.Assign)loc.instr;
                bb.copyGen.add(new TempPair(assign.dst, assign.src));
            }
        }
    }
}

public class CopyPropOpt implements Consumer<TacProg> {
    @Override
    public void accept(TacProg input) {
        var analyzer = new CopyAnalyzer();
        for(var func : input.funcs) {
            var builder = new CFGBuilder<TacInstr>();
            var cfg = builder.buildFrom(func.getInstrSeq());
            analyzer.accept(cfg);
            var instSeq = new ArrayList<TacInstr>();
            instSeq.add(func.getInstrSeq().get(0));
            for(var bb : cfg.nodes) {
                bb.label.ifPresent(e -> instSeq.add(new TacInstr.Mark(e)));
                for(var inst : bb.locs) {
                    instSeq.add(inst.instr.updateReadReg(inst.copyIn));
                }
            }
            func.setInstrSeq(instSeq);
        }
    }
}
