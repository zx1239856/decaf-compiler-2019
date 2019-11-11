package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.scope.LocalScope;
import decaf.frontend.scope.ScopeStack;
import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.*;
import decaf.lowlevel.log.IndentPrinter;
import decaf.printing.PrettyScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The typer phase: type check abstract syntax tree and annotate nodes with inferred (and checked) types.
 */
public class Typer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {

    public Typer(Config config) {
        super("typer", config);
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA2)) {
            var printer = new PrettyScope(new IndentPrinter(config.output));
            printer.pretty(tree.globalScope);
            printer.flush();
        }
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        for (var clazz : program.classes) {
            clazz.accept(this, ctx);
        }
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {
        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
        ctx.open(method.symbol.scope);
        if (method.body.isPresent()) {
            method.body.get().accept(this, ctx);
            if (!method.symbol.type.returnType.isVoidType() && !method.body.get().returns) {
                issue(new MissingReturnError(method.body.get().pos));
            }
        }
        ctx.close();
    }

    /**
     * To determine if a break statement is legal or not, we need to know if we are inside a loop, i.e.
     * loopLevel {@literal >} 1?
     * <p>
     * Increase this counter when entering a loop, and decrease it when leaving a loop.
     */
    private int loopLevel = 0;

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        ctx.open(block.scope);
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
        }
        ctx.close();
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
    }

    @Override
    public void visitAssign(Tree.Assign stmt, ScopeStack ctx) {
        stmt.lhs.accept(this, ctx);
        stmt.rhs.accept(this, ctx);
        var lt = stmt.lhs.type;
        var rt = stmt.rhs.type;

        if (stmt.lhs instanceof Tree.VarSel) {
            var lhs = (Tree.VarSel) stmt.lhs;
            if (lhs.symbol != null) {
                if (lhs.symbol.isMethodSymbol()) {
                    MethodSymbol symbol = (MethodSymbol) lhs.symbol;
                    issue(new InvalidAssignError(stmt.pos, symbol.name, true));
                } else if (lhs.symbol.isVarSymbol() && ctx.currentMethod().scope.isLambdaScope()) {
                    var symbol = (VarSymbol) lhs.symbol;
                    var scope = (LambdaScope) ctx.currentMethod().scope;
                    if (symbol.isLocalVar() && !scope.isInLambda(symbol.name)) {
                        // captured local var, forbidden
                        issue(new InvalidAssignError(stmt.pos, "", false));
                    }
                }
            }
        }

        if (lt.noError() && !rt.subtypeOf(lt)) {
            issue(new IncompatBinOpError(stmt.pos, lt.toString(), "=", rt.toString()));
        }
    }

    @Override
    public void visitExprEval(Tree.ExprEval stmt, ScopeStack ctx) {
        stmt.expr.accept(this, ctx);
    }


    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        checkTestExpr(stmt.cond, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> b.accept(this, ctx));
        // if-stmt returns a value iff both branches return
        stmt.returns = stmt.trueBranch.returns && stmt.falseBranch.isPresent() && stmt.falseBranch.get().returns;
    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        checkTestExpr(loop.cond, ctx);
        loopLevel++;
        loop.body.accept(this, ctx);
        loopLevel--;
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        checkTestExpr(loop.cond, ctx);
        loop.update.accept(this, ctx);
        loopLevel++;
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
        }
        loopLevel--;
        ctx.close();
    }

    @Override
    public void visitBreak(Tree.Break stmt, ScopeStack ctx) {
        if (loopLevel == 0) {
            issue(new BreakOutOfLoopError(stmt.pos));
        }
    }

    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        var expected = ctx.currentMethod().type.returnType;
        stmt.expr.ifPresent(e -> e.accept(this, ctx));
        var actual = stmt.expr.map(e -> e.type).orElse(BuiltInType.VOID);
        if (actual.noError() && !actual.subtypeOf(expected)) {
            issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
        }
        stmt.returns = stmt.expr.isPresent();
    }

    @Override
    public void visitPrint(Tree.Print stmt, ScopeStack ctx) {
        int i = 0;
        for (var expr : stmt.exprs) {
            expr.accept(this, ctx);
            i++;
            if (expr.type.noError() && !expr.type.isBaseType()) {
                issue(new BadPrintArgError(expr.pos, Integer.toString(i), expr.type.toString()));
            }
        }
    }

    private void checkTestExpr(Tree.Expr expr, ScopeStack ctx) {
        expr.accept(this, ctx);
        if (expr.type.noError() && !expr.type.eq(BuiltInType.BOOL)) {
            issue(new BadTestExpr(expr.pos));
        }
    }

    // Expressions

    @Override
    public void visitIntLit(Tree.IntLit that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    public void visitBoolLit(Tree.BoolLit that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    public void visitStringLit(Tree.StringLit that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    public void visitNullLit(Tree.NullLit that, ScopeStack ctx) {
        that.type = BuiltInType.NULL;
    }

    @Override
    public void visitReadInt(Tree.ReadInt readInt, ScopeStack ctx) {
        readInt.type = BuiltInType.INT;
    }

    @Override
    public void visitReadLine(Tree.ReadLine readStringExpr, ScopeStack ctx) {
        readStringExpr.type = BuiltInType.STRING;
    }

    @Override
    public void visitUnary(Tree.Unary expr, ScopeStack ctx) {
        expr.operand.accept(this, ctx);
        var t = expr.operand.type;
        if (t.noError() && !compatible(expr.op, t)) {
            // Only report this error when the operand has no error, to avoid nested errors flushing.
            issue(new IncompatUnOpError(expr.pos, Tree.opStr(expr.op), t.toString()));
        }

        // Even when it doesn't type check, we could make a fair guess based on the operator kind.
        // Let's say the operator is `-`, then one possibly wants an integer as the operand.
        // Once he/she fixes the operand, according to our type inference rule, the whole unary expression
        // must have type int! Thus, we simply _assume_ it has type int, rather than `NoType`.
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.UnaryOp op, Type operand) {
        return switch (op) {
            case NEG -> operand.eq(BuiltInType.INT); // if e : int, then -e : int
            case NOT -> operand.eq(BuiltInType.BOOL); // if e : bool, then !e : bool
        };
    }

    public Type resultTypeOf(Tree.UnaryOp op) {
        return switch (op) {
            case NEG -> BuiltInType.INT;
            case NOT -> BuiltInType.BOOL;
        };
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);
        var t1 = expr.lhs.type;
        var t2 = expr.rhs.type;
        if (t1.noError() && t2.noError() && !compatible(expr.op, t1, t2)) {
            issue(new IncompatBinOpError(expr.pos, t1.toString(), Tree.opStr(expr.op), t2.toString()));
        }
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.BinaryOp op, Type lhs, Type rhs) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            // if e1, e2 : int, then e1 + e2 : int
            return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
        }

        if (op.equals(Tree.BinaryOp.AND) || op.equals(Tree.BinaryOp.OR)) { // logic
            // if e1, e2 : bool, then e1 && e2 : bool
            return lhs.eq(BuiltInType.BOOL) && rhs.eq(BuiltInType.BOOL);
        }

        if (op.equals(Tree.BinaryOp.EQ) || op.equals(Tree.BinaryOp.NE)) { // eq
            // if e1 : T1, e2 : T2, T1 <: T2 or T2 <: T1, then e1 == e2 : bool
            return lhs.subtypeOf(rhs) || rhs.subtypeOf(lhs);
        }

        // compare
        // if e1, e2 : int, then e1 > e2 : bool
        return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
    }

    public Type resultTypeOf(Tree.BinaryOp op) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            return BuiltInType.INT;
        }
        return BuiltInType.BOOL;
    }

    @Override
    public void visitNewArray(Tree.NewArray expr, ScopeStack ctx) {
        expr.elemType.accept(this, ctx);
        expr.length.accept(this, ctx);
        var et = expr.elemType.type;
        var lt = expr.length.type;

        if (et.isVoidType()) {
            issue(new BadArrElementError(expr.elemType.pos));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.type = new ArrayType(et);
        }

        if (lt.noError() && !lt.eq(BuiltInType.INT)) {
            issue(new BadNewArrayLength(expr.length.pos));
        }
    }

    @Override
    public void visitNewClass(Tree.NewClass expr, ScopeStack ctx) {
        var clazz = ctx.lookupClass(expr.clazz.name);
        if (clazz.isPresent()) {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
            if (expr.symbol.isAbstract())
                issue(new AbstractNewError(expr.symbol.name, expr.pos));
        } else {
            issue(new ClassNotFoundError(expr.pos, expr.clazz.name));
            expr.type = BuiltInType.ERROR;
        }
    }

    @Override
    public void visitThis(Tree.This expr, ScopeStack ctx) {
        if (ctx.currentMethod().isStatic()) {
            issue(new ThisInStaticFuncError(expr.pos));
        }
        expr.type = ctx.currentClass().type;
    }

    private boolean allowClassNameVar = false;

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx) {
        if (expr.receiver.isEmpty()) {
            // Variable, which should be complicated since a legal variable could refer to a local var,
            // a visible member var, and a class name.
            var symbol = ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
            var scope = ctx.currentMethod().scope;
            if (symbol.isPresent()) {
                // check case: var a = fun() => a;
                if (!scope.isLambdaScope() || !((LambdaScope) scope).isInForbidden(expr.name)) {
                    if (symbol.get().isVarSymbol()) {
                        var var = (VarSymbol) symbol.get();
                        expr.symbol = var;
                        expr.type = var.type;
                        if (var.isMemberVar()) {
                            if (ctx.currentMethod().isStatic()) {
                                issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                            } else {
                                expr.setThis();
                            }
                        }
                        return;
                    } else if (symbol.get().isMethodSymbol()) {
                        var var = (MethodSymbol) symbol.get();
                        expr.symbol = var;
                        expr.type = var.type;
                        if (!var.isStatic()) {
                            if (ctx.currentMethod().isStatic()) {
                                issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                            } else {
                                expr.setThis();
                            }
                        }
                        return;
                    }

                    if (symbol.get().isClassSymbol() && allowClassNameVar) { // special case: a class name
                        var clazz = (ClassSymbol) symbol.get();
                        expr.type = clazz.type;
                        expr.isClassName = true;
                        return;
                    }
                }
            }

            expr.type = BuiltInType.ERROR;
            issue(new UndeclVarError(expr.pos, expr.name));
            return;
        }

        // has receiver
        var receiver = expr.receiver.get();
        allowClassNameVar = true;
        receiver.accept(this, ctx);
        allowClassNameVar = false;
        var rt = receiver.type;
        expr.type = BuiltInType.ERROR;

        if (receiver instanceof Tree.VarSel) {
            var v1 = (Tree.VarSel) receiver;
            if (v1.isClassName) {
                var clazz = ctx.getClass(v1.name);
                var symbol = clazz.scope.lookup(expr.name);
                if (symbol.isPresent()) {
                    if (symbol.get().isMethodSymbol()) {
                        var method = (MethodSymbol) symbol.get();
                        // allow ClassName.StaticMethod
                        if (!method.isStatic()) {
                            issue(new NotClassFieldError(expr.pos, expr.name, clazz.type.toString()));
                        } else {
                            expr.symbol = method;
                            expr.type = method.type;
                        }
                    } else {
                        // special case like MyClass.foo: report error cannot access field 'foo' from 'class : MyClass'
                        issue(new NotClassFieldError(expr.pos, expr.name, ctx.getClass(v1.name).type.toString()));
                    }
                } else {
                    issue(new FieldNotFoundError(expr.pos, expr.name, clazz.type.toString()));
                }
                return;
            }
        }

        if (!rt.noError()) {
            return;
        }


        if (rt.isArrayType() && expr.name.equals("length")) { // Special case: Array.length
            expr.type = new FunType(BuiltInType.INT, new ArrayList<>());
            expr.isArrayLength = true;
        } else if (rt.isClassType()) {
            var ct = (ClassType) rt;
            var field = ctx.getClass(ct.name).scope.lookup(expr.name);
            if (field.isPresent()) {
                if (field.get().isVarSymbol()) {
                    var var = (VarSymbol) field.get();
                    if (var.isMemberVar()) {
                        expr.symbol = var;
                        expr.type = var.type;
                        if (!ctx.currentClass().type.subtypeOf(var.getOwner().type)) {
                            // member vars are protected
                            issue(new FieldNotAccessError(expr.pos, expr.name, ct.toString()));
                        }
                    }
                } else if (field.get().isMethodSymbol()) {
                    // allow this.MethodName
                    var method = (MethodSymbol) field.get();
                    expr.symbol = method;
                    expr.type = method.type;
                } else {
                    issue(new NotClassFieldError(expr.pos, expr.name, ct.toString()));
                }
            } else {
                issue(new FieldNotFoundError(expr.pos, expr.name, ct.toString()));
            }
        } else {
            issue(new NotClassFieldError(expr.pos, expr.name, rt.toString()));
        }
    }

    @Override
    public void visitIndexSel(Tree.IndexSel expr, ScopeStack ctx) {
        expr.array.accept(this, ctx);
        expr.index.accept(this, ctx);
        var at = expr.array.type;
        var it = expr.index.type;

        if (!at.isArrayType()) {
            if (!at.eq(BuiltInType.ERROR))  // do not report errors cause by others
                issue(new NotArrayError(expr.array.pos));
            expr.type = BuiltInType.ERROR;
            return;
        }

        expr.type = ((ArrayType) at).elementType;
        if (!it.eq(BuiltInType.INT) && !it.eq(BuiltInType.ERROR)) {
            issue(new SubNotIntError(expr.pos));
        }
    }

    private Type getTypeBound(List<Type> typeList, boolean isUpper) {
        Type sel = null;
        for (var v : typeList) {
            if (!v.eq(BuiltInType.NULL)) {
                sel = v;
                break;
            }
        }
        if (sel == null)
            return BuiltInType.NULL;   // are all NULL types
        else if (sel.isBaseType() || sel.isArrayType() || sel.isVoidType()) {
            // should be equivalent for all
            // for basic types, upper and lower bound is no different
            for (var v : typeList) {
                if (!v.eq(sel))
                    return BuiltInType.ERROR;
            }
            return sel;
        } else if (sel.isClassType()) {
            if (isUpper) {
                var clsType = (ClassType) sel;
                while (true) {
                    boolean canChoose = true;
                    for (var v : typeList) {
                        if (!v.subtypeOf(clsType)) {
                            canChoose = false;
                            break;
                        }
                    }
                    if (canChoose)
                        return clsType;
                    else if (clsType.superType.isPresent())
                        clsType = clsType.superType.get();
                    else
                        return BuiltInType.ERROR;
                }
            } else {
                // calculate the lower bound of a list of classes
                // naive method, can be optimized
                for (var i : typeList) {
                    if (!i.isClassType())
                        return BuiltInType.ERROR;
                    boolean canChoose = true;
                    for (var j : typeList) {
                        if (!i.subtypeOf(j)) {
                            canChoose = false;
                            break;
                        }
                    }
                    if (canChoose) return i;
                }
                return BuiltInType.ERROR;
            }
        } else if (sel.isFuncType()) {
            var a = (FunType) sel;
            List<Type> returnTypeList = new ArrayList<>();
            List<List<Type>> paramTypeList = new ArrayList<>(a.arity());
            for (int i = 0; i < a.arity(); ++i)
                paramTypeList.add(new ArrayList<>());
            for (var v : typeList) {
                if (!v.isFuncType())
                    return BuiltInType.ERROR;
                else {
                    var b = (FunType) v;
                    if (a.arity() != b.arity())
                        return BuiltInType.ERROR;
                    else {
                        returnTypeList.add(b.returnType);
                        for (int k = 0; k < b.arity(); ++k)
                            paramTypeList.get(k).add(b.argTypes.get(k));
                    }
                }
            }
            // pretest okay, now calc upper and lower bound
            // for return type
            var r = getTypeBound(returnTypeList, isUpper);
            if (r.eq(BuiltInType.ERROR))
                return BuiltInType.ERROR;
            var tList = new ArrayList<Type>();
            // for param type
            for (var list : paramTypeList) {
                var t = getTypeBound(list, !isUpper);
                if (t.eq(BuiltInType.ERROR))
                    return BuiltInType.ERROR;
                tList.add(t);
            }
            return new FunType(r, tList);
        } else {
            // should never happen
            return BuiltInType.ERROR;
        }
    }

    private Type getLambdaReturnType(List<Tree.Return> returnStmts) {
        if (returnStmts.size() == 0) {
            return BuiltInType.VOID;
        } else {
            List<Type> types = new ArrayList<>();
            returnStmts.forEach(o -> types.add(o.expr.map(expr -> expr.type).orElse(BuiltInType.VOID)));
            boolean hasError = false;
            for (var v : types) {
                if (v.eq(BuiltInType.ERROR)) {
                    hasError = true;
                    break;
                }
            }
            var res = getTypeBound(types, true);
            if (!hasError && res.eq(BuiltInType.ERROR))
                return null;  // indicates newly generated err
            return res;
        }
    }

    @Override
    public void visitLambda(Tree.Lambda that, ScopeStack ctx) {
        Type returnType;
        var scope = (LambdaScope) that.symbol.scope;
        ctx.open(scope);
        if (that.isBlock()) {
            var block = that.block.get();
            block.accept(this, ctx);
            returnType = getLambdaReturnType(scope.returnStmtList);
            boolean hasErr = false;
            if (returnType == null) {
                returnType = BuiltInType.ERROR;
                hasErr = true;
            }
            if (!returnType.isVoidType() && !block.returns) {
                issue(new MissingReturnError(block.pos));
            }
            if (hasErr) {
                issue(new IncompatReturnError(block.pos));
            }
        } else {
            var expr = that.expr.get();
            expr.accept(this, ctx);
            returnType = expr.type;
        }
        that.symbol.type.returnType = returnType;
        that.type = that.symbol.type;
        ctx.close();
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {
        expr.type = BuiltInType.ERROR;

        expr.callee.accept(this, ctx);

        if (expr.callee.type.eq(BuiltInType.ERROR)) {
            expr.type = BuiltInType.ERROR;
            return;
        }

        if (!expr.callee.type.isFuncType()) {
            issue(new NonCallableTypeError(expr.callee.type.toString(), expr.pos));
        } else {
            // typing args
            var calleeType = (FunType) expr.callee.type;
            var args = expr.args;
            expr.type = calleeType.returnType;
            for (var arg : args) {
                arg.accept(this, ctx);
            }
            String calleeName = "";
            if (expr.callee instanceof Tree.VarSel) {
                var node = (Tree.VarSel) expr.callee;
                calleeName = "function '" + node.name + "'";
            } else {
                calleeName = "lambda expression";
            }
            // check signature compatibility
            if (calleeType.arity() != args.size()) {
                issue(new BadArgCountError(expr.pos, calleeName, calleeType.arity(), args.size()));
            }
            var iter1 = calleeType.argTypes.iterator();
            var iter2 = expr.args.iterator();
            for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                Type t1 = iter1.next();
                Tree.Expr e = iter2.next();
                Type t2 = e.type;
                if (t2.noError() && !t2.subtypeOf(t1)) {
                    issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                }
            }
        }
    }

    @Override
    public void visitClassTest(Tree.ClassTest expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);
        expr.type = BuiltInType.BOOL;

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }
        var clazz = ctx.lookupClass(expr.is.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.is.name));
        } else {
            expr.symbol = clazz.get();
        }
    }

    @Override
    public void visitClassCast(Tree.ClassCast expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }

        var clazz = ctx.lookupClass(expr.to.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.to.name));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        }
    }

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef stmt, ScopeStack ctx) {
        stmt.initVal.ifPresent(initVal -> {
            localVarDefPos = Optional.ofNullable(stmt.id.pos);
            initVal.accept(this, ctx);
            localVarDefPos = Optional.empty();
            var lt = stmt.symbol.type;
            var rt = initVal.type;

            if (lt == null) {
                if (initVal.type.eq(BuiltInType.VOID)) {
                    issue(new BadVarTypeError(stmt.pos, stmt.name));
                    stmt.symbol.type = BuiltInType.ERROR;
                } else {
                    stmt.symbol.type = initVal.type;
                }
            } else {
                if (lt.noError() && (!rt.subtypeOf(lt))) {
                    issue(new IncompatBinOpError(stmt.assignPos, lt.toString(), "=", rt.toString()));
                }
            }
        });
    }

    // Only usage: check if an initializer cyclically refers to the declared variable, e.g. var x = x + 1
    private Optional<Pos> localVarDefPos = Optional.empty();
}
