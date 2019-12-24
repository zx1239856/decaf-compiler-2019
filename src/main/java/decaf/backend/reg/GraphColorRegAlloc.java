package decaf.backend.reg;

import decaf.backend.asm.AsmEmitter;
import decaf.backend.asm.HoleInstr;
import decaf.backend.asm.SubroutineInfo;
import decaf.backend.asm.mips.MipsSubroutineEmitter;
import decaf.backend.dataflow.CFG;
import decaf.backend.dataflow.CFGBuilder;
import decaf.backend.dataflow.LivenessAnalyzer;
import decaf.backend.dataflow.TempPair;
import decaf.lowlevel.Mips;
import decaf.lowlevel.instr.PseudoInstr;
import decaf.lowlevel.instr.Reg;
import decaf.lowlevel.instr.Temp;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

public class GraphColorRegAlloc extends RegAlloc {
    private Set<Temp> initial;
    private Set<Temp> simplifyWorkList;
    private Set<Temp> freezeWorkList;
    private Set<Temp> spillWorkList;
    private Set<Temp> spilledNodes;
    private Set<Temp> coalescedNodes;
    private Set<Temp> coloredNodes;
    private Stack<Temp> selectStack;

    private Set<Mips.Move> coalescedMoves;
    private Set<Mips.Move> constrainedMoves;
    private Set<Mips.Move> frozenMoves;
    private Set<Mips.Move> workListMoves;
    private Set<Mips.Move> activeMoves;

    private Set<TempPair> adjSet;
    private Map<Temp, Set<Temp>> adjList;
    private Map<Temp, Integer> degree;

    private Map<Temp, Set<Mips.Move>> moveList;
    private Map<Temp, Temp> alias;
    private Map<Temp, Reg> coloredTemp;

    private final int K;

    public GraphColorRegAlloc(AsmEmitter emitter) {
        super(emitter);
        for (var reg : emitter.allocatableRegs) {
            reg.used = false;
        }
        K = emitter.allocatableRegs.length;
    }

    @Override
    public void accept(Pair<List<PseudoInstr>, SubroutineInfo> input) {
        initial = new HashSet<>();

        var instrList = new ArrayList<PseudoInstr>();

        var backPatchList = new ArrayList<Mips.LoadWord>();
        // add label
        if (input.getLeft().size() > 0)
            instrList.add(input.getLeft().get(0));

        // handle func args
        var info = input.getRight();
        for (int i = 0; i < info.numArg; ++i) {
            if (i < Mips.argRegs.length)
                instrList.add(new Mips.Move(new Temp(i), Mips.argRegs[i]));
            else {
                var inst = new Mips.LoadWord(new Temp(i), Mips.SP, 0);
                instrList.add(inst); // don't know offset now
                backPatchList.add(inst);
            }
        }
        for (int i = 1; i < input.getLeft().size(); ++i) {
            instrList.add(input.getLeft().get(i));
        }

        do {
            simplifyWorkList = new HashSet<>();
            freezeWorkList = new HashSet<>();
            spillWorkList = new HashSet<>();
            spilledNodes = new HashSet<>();
            coalescedNodes = new HashSet<>();
            coloredNodes = new HashSet<>();
            selectStack = new Stack<>();

            coalescedMoves = new HashSet<>();
            constrainedMoves = new HashSet<>();
            frozenMoves = new HashSet<>();
            workListMoves = new HashSet<>();
            activeMoves = new HashSet<>();

            moveList = new HashMap<>();
            alias = new HashMap<>();

            adjSet = new HashSet<>();
            adjList = new HashMap<>();
            degree = new HashMap<>();

            coloredTemp = new HashMap<>();

            var analyzer = new LivenessAnalyzer<>();
            var builder = new CFGBuilder<>();
            var cfg = builder.buildFrom(instrList);
            analyzer.accept(cfg);

            for (var instr : instrList) {
                for (var reg : instr.getRead()) {
                    if (!(reg instanceof Reg))
                        initial.add(reg);
                }
                for (var reg : instr.getWritten()) {
                    if (!(reg instanceof Reg))
                        initial.add(reg);
                }
            }

            build(cfg);
            makeWorkList();

            do {
                if (!simplifyWorkList.isEmpty())
                    simplify();
                else if (!workListMoves.isEmpty())
                    coalesce();
                else if (!freezeWorkList.isEmpty())
                    freeze();
                else if (!spillWorkList.isEmpty())
                    selectSpill();
            } while (!(simplifyWorkList.isEmpty() && workListMoves.isEmpty() && freezeWorkList.isEmpty() && spillWorkList.isEmpty()));

            assignColors();

            if (spilledNodes.isEmpty()) {
                // no spill, now output asm
                var subEmitter = emitter.emitSubroutine(input.getRight());
                if (subEmitter instanceof MipsSubroutineEmitter) {
                    ((MipsSubroutineEmitter) subEmitter).setBruteForce(false);
                }

                for (int i = 0; i < backPatchList.size(); ++i) {
                    backPatchList.get(i).setOffset(subEmitter.getNextLocalOffset() + 4 * (i + Mips.argRegs.length));
                }

                for (var bb : cfg) {
                    bb.label.ifPresent(subEmitter::emitLabel);

                    for (var loc : bb.locs) {
                        if (loc.instr instanceof HoleInstr)
                            continue; // ignore because we do not need them

                        // For normal instructions: allocate according to the results of graph coloring
                        var instr = loc.instr;
                        var srcRegs = new Reg[instr.srcs.length];
                        var dstRegs = new Reg[instr.dsts.length];

                        for (var i = 0; i < instr.srcs.length; i++) {
                            var temp = instr.srcs[i];
                            if (temp instanceof Reg) {
                                srcRegs[i] = (Reg) temp;
                            } else {
                                srcRegs[i] = coloredTemp.get(temp);
                            }
                        }

                        for (var i = 0; i < instr.dsts.length; i++) {
                            var temp = instr.dsts[i];
                            if (temp instanceof Reg) {
                                dstRegs[i] = ((Reg) temp);
                            } else {
                                dstRegs[i] = coloredTemp.get(temp);
                            }
                        }
                        if (instr instanceof Mips.Move && dstRegs[0].equals(srcRegs[0]))
                            continue;
                        instr.toNative(dstRegs, srcRegs).ifPresent(subEmitter::emitNative);
                    }
                }
                subEmitter.emitEnd();
                break;
            } else
                rewriteProgram();
        } while (true);
    }

    private void rewriteProgram() {
        initial = new HashSet<>(coloredNodes);
        initial.addAll(coalescedNodes);
        // according to the paper, create a new temp for each spilled register in spilledNodes
        // I do not want to implement this since this framework does not offer a convenient way to do so
        // Plus, this does not hamper the completeness of graph coloring algorithm
        // initial = coloredNodes \cap coalescedNodes \cap newTemps
        throw new IllegalStateException("Register spill is not implemented but spill occurs when trying to allocate regs.");
    }

    private void assignColors() {
        var tmp = new HashSet<>(List.of(Mips.allocatableRegs));
        while (!selectStack.empty()) {
            var n = selectStack.pop();
            var okColors = new HashSet<>(tmp);
            for (var w : adjList.getOrDefault(n, new HashSet<>())) {
                var alias = getAlias(w);
                if (alias instanceof Reg)
                    okColors.remove(alias);
                else if (coloredNodes.contains(alias))
                    okColors.remove(coloredTemp.get(alias));
            }
            var any = okColors.stream().findAny();
            any.ifPresentOrElse(v -> {
                coloredNodes.add(n);
                coloredTemp.put(n, v);
            }, () -> spilledNodes.add(n));
        }
        for (var n : coalescedNodes) {
            var alias = getAlias(n);
            var idx = Optional.ofNullable(coloredTemp.get(alias));
            idx.ifPresentOrElse(v -> coloredTemp.put(n, v), () -> coloredTemp.put(n, (Reg) alias));
        }
        for (var entry : coloredTemp.entrySet()) {
            entry.getValue().used = true;
        }
    }

    private void selectSpill() {
        int deg = 0;
        for (var m : spillWorkList) {
            if (degree.get(m) > deg)
                deg = degree.get(m);
        }
        for (var m : spillWorkList) {
            if (degree.get(m) == deg) {
                spillWorkList.remove(m);
                simplifyWorkList.add(m);
                freezeMoves(m);
                break;
            }
        }
    }

    private void freeze() {
        for (var u : freezeWorkList) {
            freezeWorkList.remove(u);
            simplifyWorkList.add(u);
            freezeMoves(u);
            break;
        }
    }

    private void freezeMoves(Temp u) {
        for (var m : nodeMoves(u)) {
            Temp v;
            if (m.dst.equals(u))
                v = m.src;
            else if (m.src.equals(u))
                v = m.dst;
            else
                continue;
            if (activeMoves.contains(m))
                activeMoves.remove(m);
            else
                workListMoves.remove(m);
            frozenMoves.add(m);
            if (nodeMoves(v).isEmpty() && degree.get(v) < K) {
                freezeWorkList.remove(v);
                simplifyWorkList.add(v);
            }
        }
    }

    private void coalesce() {
        for (var m : workListMoves) {
            var x = getAlias(m.dst);
            var y = getAlias(m.src);
            Temp u, v;
            if (y instanceof Reg) {
                u = y;
                v = x;
            } else {
                u = x;
                v = y;
            }
            workListMoves.remove(m);
            if (u.equals(v)) {
                coalescedMoves.add(m);
                addWorkList(u);
            } else if (v instanceof Reg || adjSet.contains(new TempPair(u, v))) {
                constrainedMoves.add(m);
                addWorkList(u);
                addWorkList(v);
            } else if ((u instanceof Reg && checkAdjOkay(v, u)) || (!(u instanceof Reg) && checkConservative(u, v))) {
                coalescedMoves.add(m);
                combine(u, v);
                addWorkList(u);
            } else
                activeMoves.add(m);
            break;
        }
    }

    private boolean checkConservative(Temp u, Temp v) {
        var adj = adjacent(u);
        adj.addAll(adjacent(v));
        var k = 0;
        for (var n : adj) {
            if (degree.get(n) >= K) ++k;
        }
        return k < K;
    }

    private boolean checkAdjOkay(Temp v, Temp u) {
        for (var t : adjacent(v)) {
            if (degree.get(t) < K || t instanceof Reg || adjSet.contains(new TempPair(t, u)))
                continue;
            else
                return false;
        }
        return true;
    }

    private void addWorkList(Temp u) {
        if (!(u instanceof Reg) && !moveRelated(u) && degree.get(u) < K) {
            freezeWorkList.remove(u);
            simplifyWorkList.add(u);
        }
    }

    private void simplify() {
        for (var n : simplifyWorkList) {
            simplifyWorkList.remove(n);
            selectStack.push(n);
            for (var m : adjacent(n))
                decrementDegree(m);
            break;
        }
    }


    private void _addEdgeHelper(Temp u, Temp v) {
        adjList.get(u).add(v);
        degree.put(u, degree.get(u) + 1);
    }

    private void addEdge(Temp u, Temp v) {
        initializeSet(u);
        initializeSet(v);
        var pair = new TempPair(u, v);
        if (!adjSet.contains(pair) && !u.equals(v)) {
            adjSet.add(pair);
            adjSet.add(new TempPair(v, u));
            if (!(u instanceof Reg)) {
                _addEdgeHelper(u, v);
            }
            if (!(v instanceof Reg)) {
                _addEdgeHelper(v, u);
            }
        }
    }

    private void initializeSet(Temp u) {
        if (!degree.containsKey(u))
            degree.put(u, (u instanceof Reg) ? Integer.MAX_VALUE : 0);
        if (!adjList.containsKey(u))
            adjList.put(u, new HashSet<>());
    }

    private void build(CFG<PseudoInstr> cfg) {
        for (var bb : cfg) {
            var it = bb.backwardIterator();
            while (it.hasNext()) {
                var loc = it.next();
                var live = new HashSet<>(loc.liveOut);
                // initial null ptr
                var def = loc.instr.getWritten();
                var use = loc.instr.getRead();
                for (var n : def)
                    initializeSet(n);
                for (var n : use)
                    initializeSet(n);
                if (loc.instr instanceof Mips.Move) {
                    live.removeIf(use::contains);
                    var tmp = new HashSet<>(def);
                    tmp.addAll(use);
                    for (var n : tmp) {
                        if (moveList.containsKey(n))
                            moveList.get(n).add((Mips.Move) loc.instr);
                        else
                            moveList.put(n, new HashSet<>(List.of((Mips.Move) loc.instr)));
                    }
                    workListMoves.add((Mips.Move) loc.instr);
                }
                for (var d : def) {
                    for (var l : live) {
                        addEdge(l, d);
                    }
                }
            }
        }
    }

    private HashSet<Temp> adjacent(Temp n) {
        var curr = new HashSet<>(adjList.getOrDefault(n, new HashSet<>()));
        var union = new HashSet<>(selectStack);
        union.addAll(coalescedNodes);
        curr.removeIf(union::contains);
        return curr;
    }

    private HashSet<Mips.Move> nodeMoves(Temp n) {
        var moveListCurr = moveList.getOrDefault(n, new HashSet<>());
        var tmp = new HashSet<>(activeMoves);
        tmp.addAll(workListMoves);
        tmp.retainAll(moveListCurr);
        return tmp;
    }

    private boolean moveRelated(Temp n) {
        return !nodeMoves(n).isEmpty();
    }

    private void makeWorkList() {
        for (var n : initial) {
            if (degree.get(n) >= K)
                spillWorkList.add(n);
            else if (moveRelated(n))
                freezeWorkList.add(n);
            else
                simplifyWorkList.add(n);
        }
        initial.clear();
    }

    private void decrementDegree(Temp m) {
        var d = degree.getOrDefault(m, null);
        if (d == null)
            return;
        degree.put(m, d - 1);
        if (d == K) {
            var adj = adjacent(m);
            adj.add(m);
            enableMoves(adj);
            spillWorkList.remove(m);
            if (moveRelated(m))
                freezeWorkList.add(m);
            else
                simplifyWorkList.add(m);
        }
    }

    private void enableMoves(HashSet<Temp> nodes) {
        for (var n : nodes) {
            for (var m : nodeMoves(n)) {
                if (activeMoves.contains(m)) {
                    activeMoves.remove(m);
                    workListMoves.add(m);
                }
            }
        }
    }

    private Temp getAlias(Temp n) {
        if (coalescedNodes.contains(n))
            return getAlias(alias.get(n));
        else
            return n;
    }

    private void combine(Temp u, Temp v) {
        if (freezeWorkList.contains(v))
            freezeWorkList.remove(v);
        else
            spillWorkList.remove(v);
        coalescedNodes.add(v);
        alias.put(v, u);
        for (var t : adjacent(v)) {
            addEdge(t, u);
            decrementDegree(t);
        }
        if (degree.get(u) >= K && freezeWorkList.contains(u)) {
            freezeWorkList.remove(u);
            spillWorkList.add(u);
        }
    }
}
