package decaf.backend.opt;

import decaf.backend.dataflow.CFGBuilder;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacProg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


class IntValue implements Cloneable {
    public enum Kind {NAC, UNDEF, CONST}

    ;
    public Kind kind;
    public int value;

    IntValue(Kind kind, int v) {
        this.kind = kind;
        this.value = v;
    }

    IntValue(Kind kind) {
        this.kind = kind;
        this.value = 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (!(obj instanceof IntValue))
            return false;
        var o = (IntValue) obj;
        if (this.kind == o.kind) {
            return o.kind != Kind.CONST || this.value == o.value;
        } else {
            return false;
        }
    }

    @Override
    public Object clone() {
        return new IntValue(this.kind, this.value);
    }
}

public class ConstPropOpt implements Consumer<TacProg> {
    private IntValue meet(IntValue a, IntValue b) {
        if (a.kind == IntValue.Kind.CONST && b.kind == IntValue.Kind.CONST && a.value == b.value)
            return new IntValue(IntValue.Kind.CONST, a.value);
        else if (a.kind == IntValue.Kind.UNDEF)
            return (IntValue) b.clone();
        else if (b.kind == IntValue.Kind.UNDEF)
            return (IntValue) a.clone();
        else
            return new IntValue(IntValue.Kind.NAC);
    }

    private int evalUnary(TacInstr.Unary.Op op, int val) {
        switch (op) {
            case NEG -> {
                return -val;
            }
            case LNOT -> {
                return val == 0 ? 1 : 0;
            }
            default -> {
                return 0;
            }
        }
    }

    private int evalBinary(TacInstr.Binary.Op op, int lhs, int rhs) {
        switch (op) {
            case ADD -> {
                return lhs + rhs;
            }
            case SUB -> {
                return lhs - rhs;
            }
            case MUL -> {
                return lhs * rhs;
            }
            case DIV -> {
                return rhs == 0 ? 0 : lhs / rhs;
            }
            case MOD -> {
                return rhs == 0 ? 0 : lhs % rhs;
            }
            case EQU -> {
                return lhs == rhs ? 1 : 0;
            }
            case NEQ -> {
                return lhs != rhs ? 1 : 0;
            }
            case LES -> {
                return lhs < rhs ? 1 : 0;
            }
            case LEQ -> {
                return lhs <= rhs ? 1 : 0;
            }
            case GTR -> {
                return lhs > rhs ? 1 : 0;
            }
            case GEQ -> {
                return lhs >= rhs ? 1 : 0;
            }
            case LAND -> {
                return lhs == 0 || rhs == 0 ? 0 : 1;
            }
            case LOR -> {
                return lhs == 0 && rhs == 0 ? 0 : 1;
            }
            default -> {
                return 0;
            }
        }
    }

    private TacInstr transform(TacInstr inst, List<IntValue> values) {
        switch (inst.type) {
            case BINARY -> {
                var bin = (TacInstr.Binary) inst;
                var l = values.get(bin.lhs.index);
                var r = values.get(bin.rhs.index);
                var dst = bin.dst;
                if (l.kind == IntValue.Kind.CONST && r.kind == IntValue.Kind.CONST) {
                    // evaluate
                    int val = evalBinary(bin.op, l.value, r.value);
                    values.get(dst.index).kind = IntValue.Kind.CONST;
                    values.get(dst.index).value = val;
                    return new TacInstr.LoadImm4(dst, val);
                } else if (l.kind == IntValue.Kind.NAC || r.kind == IntValue.Kind.NAC) {
                    values.get(dst.index).kind = IntValue.Kind.NAC;
                } else {
                    values.get(dst.index).kind = IntValue.Kind.UNDEF;
                }
                if (r.kind == IntValue.Kind.CONST) {
                    switch (bin.op) {
                        // x + 0 = x, x - 0 = x, x * 1 = x, x * 0 = 0, x & 0 = 0, x | 1 = 1
                        case ADD, SUB -> {
                            if (r.value == 0) {
                                values.get(dst.index).kind = l.kind;
                                values.get(dst.index).value = l.value;
                                return new TacInstr.Assign(dst, bin.lhs);
                            }
                        }
                        case MUL -> {
                            if (r.value == 0) {
                                values.get(dst.index).kind = IntValue.Kind.CONST;
                                values.get(dst.index).value = 0;
                                return new TacInstr.LoadImm4(dst, 0);
                            } else if (r.value == 1) {
                                values.get(dst.index).kind = l.kind;
                                values.get(dst.index).value = l.value;
                                return new TacInstr.Assign(dst, bin.lhs);
                            }
                        }
                        case LAND -> {
                            if (r.value == 0) {
                                values.get(dst.index).kind = IntValue.Kind.CONST;
                                values.get(dst.index).value = 0;
                                return new TacInstr.LoadImm4(dst, 0);
                            }
                        }
                        case LOR -> {
                            if (r.value != 0) {
                                values.get(dst.index).kind = IntValue.Kind.CONST;
                                values.get(dst.index).value = 1;
                                return new TacInstr.LoadImm4(dst, 1);
                            }
                        }
                    }
                } else if (l.kind == IntValue.Kind.CONST) {
                    switch (bin.op) {
                        // 0 + x = x, 1 * x = x, 0 * x = 0, 0 & x = 0, 1 | x = 1, 0 / x = 0, 0 % x = 0
                        case ADD -> {
                            if (l.value == 0) {
                                values.get(dst.index).kind = r.kind;
                                values.get(dst.index).value = r.value;
                                return new TacInstr.Assign(dst, bin.rhs);
                            }
                        }
                        case MUL -> {
                            if (l.value == 0) {
                                values.get(dst.index).kind = IntValue.Kind.CONST;
                                values.get(dst.index).value = 0;
                                return new TacInstr.LoadImm4(dst, 0);
                            } else if (l.value == 1) {
                                values.get(dst.index).kind = r.kind;
                                values.get(dst.index).value = r.value;
                                return new TacInstr.Assign(dst, bin.rhs);
                            }
                        }
                        case LAND, DIV, MOD -> {
                            if (l.value == 0) {
                                values.get(dst.index).kind = IntValue.Kind.CONST;
                                values.get(dst.index).value = 0;
                                return new TacInstr.LoadImm4(dst, 0);
                            }
                        }
                        case LOR -> {
                            if (l.value != 0) {
                                values.get(dst.index).kind = IntValue.Kind.CONST;
                                values.get(dst.index).value = 1;
                                return new TacInstr.LoadImm4(dst, 1);
                            }
                        }
                    }
                }
                return inst;
            }
            case UNARY -> {
                var unary = (TacInstr.Unary) inst;
                var operand = values.get(unary.operand.index);
                var dst = unary.dst;
                if (operand.kind == IntValue.Kind.CONST) {
                    int val = evalUnary(unary.op, operand.value);
                    values.get(dst.index).kind = IntValue.Kind.CONST;
                    values.get(dst.index).value = val;
                    return new TacInstr.LoadImm4(dst, val);
                } else if (operand.kind == IntValue.Kind.NAC) {
                    values.get(dst.index).kind = IntValue.Kind.NAC;
                } else {
                    values.get(dst.index).kind = IntValue.Kind.UNDEF;
                }
                return inst;
            }
            case LOAD_IMM -> {
                var imm = (TacInstr.LoadImm4) inst;
                values.get(imm.dst.index).kind = IntValue.Kind.CONST;
                values.get(imm.dst.index).value = imm.value;
                return inst;
            }
            case ASSIGN -> {
                var assign = (TacInstr.Assign) inst;
                var src = values.get(assign.src.index);
                values.set(assign.dst.index, src);
                if (src.kind == IntValue.Kind.CONST)
                    return new TacInstr.LoadImm4(assign.dst, src.value);
                else
                    return inst;
            }
            case LOAD, DIRECT_CALL, INDIRECT_CALL -> {
                if (inst.dsts.length > 0)
                    values.get(inst.dsts[0].index).kind = IntValue.Kind.NAC;
                return inst;
            }
            case LOAD_STR, LOAD_VTBL -> {
                values.get(inst.dsts[0].index).kind = IntValue.Kind.UNDEF;
                return inst;
            }
            case COND_BRANCH -> {
                var br = (TacInstr.CondBranch) inst;
                var val = values.get(br.cond.index);
                if (val.kind == IntValue.Kind.CONST) {
                    if (br.op == TacInstr.CondBranch.Op.BNEZ) {
                        return val.value != 0 ? new TacInstr.Branch(br.target) : null;
                    } else {
                        return val.value == 0 ? new TacInstr.Branch(br.target) : null;
                    }
                } else
                    return inst;
            }
            default -> {
                return inst;
            }
        }
    }

    public void accept(TacProg input) {
        for (var func : input.funcs) {
            var builder = new CFGBuilder<TacInstr>();
            var cfg = builder.buildFrom(func.getInstrSeq());
            var each = func.getUsedTempCount();
            var flow = new ArrayList<IntValue>(each * cfg.nodes.size());
            for (int i = 0; i < each * cfg.nodes.size(); ++i) {
                flow.add(new IntValue(IntValue.Kind.UNDEF));
            }
            for (int i = 0; i < func.numArgs; ++i)
                flow.get(i).kind = IntValue.Kind.NAC;
            var temp = new ArrayList<>();
            for (var i : flow)
                temp.add(i.clone());
            while (true) {
                for (int idx = 0; idx < cfg.nodes.size(); ++idx) {
                    for (var next : cfg.getSucc(idx)) {
                        var off = idx * each;
                        var off1 = next * each;
                        for (int i = 0; i < each; ++i)
                            flow.set(off1 + i, meet(flow.get(off + i), flow.get(off1 + i)));
                    }
                }
                for (int idx = 0; idx < cfg.nodes.size(); ++idx) {
                    var arr = flow.subList(idx * each, (idx + 1) * each);
                    for (var loc : cfg.nodes.get(idx).locs)
                        transform(loc.instr, arr);
                }
                boolean hasChange = false;
                for (int i = 0; i < flow.size(); ++i) {
                    if (!flow.get(i).equals(temp.get(i))) {
                        hasChange = true;
                        break;
                    }
                }
                if (!hasChange)
                    break;
                else {
                    temp = new ArrayList<>();
                    for (var i : flow)
                        temp.add(i.clone());
                }
            }
            var instSeq = new ArrayList<TacInstr>();
            instSeq.add(func.getInstrSeq().get(0));
            for (int idx = 0; idx < cfg.nodes.size(); ++idx) {
                cfg.nodes.get(idx).label.ifPresent(e -> instSeq.add(new TacInstr.Mark(e)));
                var arr = flow.subList(idx * each, (idx + 1) * each);
                for (var loc : cfg.nodes.get(idx).locs) {
                    var res = transform(loc.instr, arr);
                    if (res != null) {
                        res.hint = loc.instr.hint;
                        instSeq.add(res);
                    }
                }
            }
            func.setInstrSeq(instSeq);
        }
    }
}
