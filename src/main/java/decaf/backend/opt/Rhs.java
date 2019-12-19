package decaf.backend.opt;

import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.VTable;

public abstract class Rhs {
    protected Rhs(Type type) {
        this.type = type;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    public abstract boolean uses(Temp temp);

    public enum Type {
        LOAD_VTBL, LOAD_STR_CONST, LOAD, BINARY, UNARY, NO_TYPE
    }
    public final Type type;

    static class LoadRhs extends Rhs {
        public final Temp base;
        public final int offset;

        public LoadRhs(TacInstr.Memory instr) {
            super(Type.LOAD);
            base = instr.base;
            offset = instr.offset;
        }

        @Override
        public String toString() {
            var sign = offset >= 0 ? "+" : "-";
            var value = offset >= 0 ? offset : -offset;
            return String.format("*(%s %s %d)", base, sign, value);
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == this)
                return true;
            if(!(obj instanceof LoadRhs))
                return false;
            LoadRhs o = (LoadRhs) obj;
            return this.base.compareTo(o.base) == 0 && this.offset == o.offset;
        }

        @Override
        public boolean uses(Temp temp) {
            return base.compareTo(temp) == 0;
        }
    }

    static class LoadVtblRhs extends Rhs {
        public final VTable vtbl;

        public LoadVtblRhs(TacInstr.LoadVTbl instr) {
            super(Type.LOAD_VTBL);
            vtbl = instr.vtbl;
        }

        @Override
        public String toString() {
            return vtbl.label.prettyString();
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == this)
                return true;
            if(!(obj instanceof LoadVtblRhs))
                return false;
            LoadVtblRhs o = (LoadVtblRhs) obj;
            return o.toString().equals(this.toString());
        }

        @Override
        public boolean uses(Temp temp) {
            return false;
        }
    }

    static class LoadStrRhs extends Rhs {
        public final String value;

        public LoadStrRhs(TacInstr.LoadStrConst instr) {
            super(Type.LOAD_STR_CONST);
            value = instr.value;
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == this)
                return true;
            if(!(obj instanceof LoadStrRhs))
                return false;
            LoadStrRhs o = (LoadStrRhs) obj;
            return this.value.equals(o.value);
        }

        @Override
        public boolean uses(Temp temp) {
            return false;
        }
    }

    static class UnaryRhs extends Rhs {
        public final TacInstr.Unary.Op op;
        public final Temp operand;

        public UnaryRhs(TacInstr.Unary instr) {
            super(Type.UNARY);
            op = instr.op;
            operand = instr.operand;
        }

        @Override
        public String toString() {
            var opStr = switch (op) {
                case NEG -> "-";
                case LNOT -> "!";
            };
            return String.format("%s %s", opStr, operand);
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == this)
                return true;
            if(!(obj instanceof UnaryRhs))
                return false;
            UnaryRhs o = (UnaryRhs) obj;
            return this.op == o.op && this.operand.compareTo(o.operand) == 0;
        }

        @Override
        public boolean uses(Temp temp) {
            return operand.compareTo(temp) == 0;
        }
    }

    static class BinaryRhs extends Rhs {
        public final TacInstr.Binary.Op op;
        public final Temp lhs;
        public final Temp rhs;

        public BinaryRhs(TacInstr.Binary instr) {
            super(Type.BINARY);
            op = instr.op;
            lhs = instr.lhs;
            rhs = instr.rhs;
        }

        @Override
        public String toString() {
            var opStr = switch (op) {
                case ADD -> "+";
                case SUB -> "-";
                case MUL -> "*";
                case DIV -> "/";
                case MOD -> "%";
                case EQU -> "==";
                case NEQ -> "!=";
                case LES -> "<";
                case LEQ -> "<=";
                case GTR -> ">";
                case GEQ -> ">=";
                case LAND -> "&&";
                case LOR -> "||";
            };
            return String.format("%s %s %s", lhs, opStr, rhs);
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == this)
                return true;
            if(!(obj instanceof BinaryRhs))
                return false;
            BinaryRhs o = (BinaryRhs) obj;
            return this.op == o.op && this.lhs.compareTo(o.lhs) == 0 && this.rhs.compareTo(o.rhs) == 0;
        }

        @Override
        public boolean uses(Temp temp) {
            return lhs.compareTo(temp) == 0 || rhs.compareTo(temp) == 0;
        }
    }
}