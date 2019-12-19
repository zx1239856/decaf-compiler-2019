package decaf.backend.opt;

import decaf.backend.dataflow.BasicBlock;
import decaf.backend.dataflow.CFG;
import decaf.backend.dataflow.CFGBuilder;
import decaf.backend.dataflow.Loc;
import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacProg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Consumer;

class CommonExprAnalyzer implements Consumer<CFG<TacInstr>> {
    @Override
    public void accept(CFG<TacInstr> graph) {
        var uSet = new HashSet<Rhs>();
        for(var bb : graph.nodes) {
            computeGenKill(bb);
            bb.out = new HashSet<>();
            bb.out.addAll(bb.gen);
            uSet.addAll(bb.gen);
            bb.in = new HashSet<>();
        }

        var changed = true;

        for(int i = 1; i < graph.nodes.size(); ++i) {
            graph.nodes.get(i).in.addAll(uSet);
        }

        do {
            changed = false;
            for(var bb : graph.nodes) {
                for(var prev : graph.getPrev(bb.id)) {
                    bb.in.retainAll(graph.getBlock(prev).out);
                }
                var temp = new HashSet<>(bb.in);
                temp.removeIf(v -> bb.kill.contains(v));
                if(bb.out.addAll(temp))
                    changed = true;
            }
        } while (changed);

        for(var bb : graph.nodes)
            analyzeInOutForEachLoc(bb);
    }

    static Optional<Rhs> getRhs(TacInstr instr) {
        Optional<Rhs> res = Optional.empty();
        switch(instr.type) {
            case LOAD_VTBL -> res = Optional.of(new Rhs.LoadVtblRhs((TacInstr.LoadVTbl) instr));
            case LOAD_STR -> res = Optional.of(new Rhs.LoadStrRhs((TacInstr.LoadStrConst)instr));
            case LOAD -> {
                if(instr.hint == TacInstr.CompilerHint.IMMUTABLE_LOAD) {
                    var load = (TacInstr.Memory)instr;
                    if(load.base.compareTo(load.dst) != 0)
                        res = Optional.of(new Rhs.LoadRhs(load));
                }
            }
            // warning: x = x + y cannot gen x + y
            case BINARY -> {
                var bin = (TacInstr.Binary)instr;
                if(bin.dst.compareTo(bin.lhs) != 0 && bin.dst.compareTo(bin.rhs) != 0)
                    res = Optional.of(new Rhs.BinaryRhs(bin));
            }
            case UNARY -> {
                var una = (TacInstr.Unary)instr;
                if(una.dst.compareTo(una.operand) != 0)
                    res = Optional.of(new Rhs.UnaryRhs((TacInstr.Unary)instr));
            }
        }
        return res;
    }

    static private void dfsInner(ArrayList<Boolean> visited, CFG<TacInstr> graph, int currBlk, int currLoc, Rhs rhs, Temp newReg) {
        visited.set(currBlk, true);
        var bb = graph.getBlock(currBlk);
        for(int i = currLoc; i >= 0; --i) {
            var loc = bb.locs.get(i);
            var tmp = CommonExprAnalyzer.getRhs(loc.instr);
            if(tmp.isPresent() && tmp.get().equals(rhs)) {
                // update
                var oldDst = loc.instr.getWritten().get(0);
                loc.instr = loc.instr.updateWriteReg(newReg);
                bb.locs.add(i + 1, new Loc<>(new TacInstr.Assign(oldDst, newReg)));
                return;
            }
        }
        // not found, dfs to prev
        for(var prev : graph.getPrev(currBlk)) {
            if(!visited.get(prev))
                dfsInner(visited, graph, prev, graph.getBlock(prev).locs.size() - 1, rhs, newReg);
        }
    }

    static void dfs(CFG<TacInstr> graph, int currBlk, int currLoc, Rhs rhs, Temp newReg) {
        var visited = new ArrayList<Boolean>(graph.nodes.size());
        for(int i = 0; i < graph.nodes.size(); ++i)
            visited.add(false);
        CommonExprAnalyzer.dfsInner(visited, graph, currBlk, currLoc - 1, rhs, newReg);
    }

    private void analyzeInOutForEachLoc(BasicBlock<TacInstr> bb) {
        var in = new HashSet<>(bb.in);
        for(var loc : bb.locs) {
            loc.in = new HashSet<>(in);
            if(loc.instr.getWritten().size() > 0)
                in.removeIf(v -> v.uses(loc.instr.getWritten().get(0)));
            getRhs(loc.instr).ifPresent(in::add);
            loc.out = new HashSet<>(in);
        }
    }

    private void computeGenKill(BasicBlock<TacInstr> bb) {
        bb.gen = new HashSet<>();
        bb.kill = new HashSet<>();
        for(var loc : bb.locs) {
            // add kill
            bb.kill.addAll(loc.instr.getWritten());
            // remove kill from gen
            if(loc.instr.getWritten().size() > 0)
                bb.gen.removeIf(v -> v.uses(loc.instr.getWritten().get(0)));
            // add gen
            getRhs(loc.instr).ifPresent(bb.gen::add);
        }
    }
}

public class CommonExprOpt implements Consumer<TacProg> {
    public void accept(TacProg input) {
        var analyzer = new CommonExprAnalyzer();
        for(var func : input.funcs) {
            var builder = new CFGBuilder<TacInstr>();
            var cfg = builder.buildFrom(func.getInstrSeq());
            analyzer.accept(cfg);

            for(var bb : cfg.nodes) {
                for(int i = 0; i < bb.locs.size(); ++i) {
                    var curr = bb.locs.get(i);
                    var rhs = CommonExprAnalyzer.getRhs(curr.instr);
                    if(rhs.isPresent() && curr.in.contains(rhs.get())) {
                        int oldSize = bb.locs.size();
                        Temp reg = func.getFreshTemp();
                        CommonExprAnalyzer.dfs(cfg, bb.id, i, rhs.get(), reg);
                        i += (bb.locs.size() - oldSize);
                        // new index of curr
                        var oldDst = curr.instr.getWritten().get(0);
                        curr.instr = new TacInstr.Assign(oldDst, reg);
                    }
                }
            }

            var instSeq = new ArrayList<TacInstr>();
            instSeq.add(func.getInstrSeq().get(0));
            for(var bb : cfg.nodes) {
                bb.label.ifPresent(e -> instSeq.add(new TacInstr.Mark(e)));
                for(var loc : bb.locs) {
                    instSeq.add(loc.instr);
                }
            }
            func.setInstrSeq(instSeq);
        }
    }
}
