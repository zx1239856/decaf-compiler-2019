package decaf.frontend.scope;

import decaf.frontend.symbol.Symbol;
import decaf.frontend.tree.Tree;

import java.util.*;

public class LambdaScope extends FormalScope {
    public List<Tree.Return> returnStmtList = new ArrayList<>(); // for return type deduction

    public LambdaScope(Scope parent, Scope parentMethodScope) {
        super();
        assert parent.isLocalScope();
        var par = (LocalScope) parent;
        par.nestedScopes.add(this);
        if(parentMethodScope.isLambdaScope()) {
            this.forbiddenSymbols.putAll(((LambdaScope) parentMethodScope).forbiddenSymbols);
        }
    }

    @Override
    public boolean isLambdaScope() {
        return true;
    }

    public void putInLambda(Symbol symbol) {
        lambdaSymbols.put(symbol.name, symbol);
    }

    public void putInForbidden(Symbol symbol) {
        forbiddenSymbols.put(symbol.name, symbol);
    }

    public boolean isInLambda(String name) {
        return lambdaSymbols.containsKey(name);
    }

    public boolean isInForbidden(String name) {
        return forbiddenSymbols.containsKey(name);
    }

    public void putInCaptured(Symbol symbol) {
        capturedSymbols.put(symbol.name, symbol);
    }

    public Map<String, Symbol> getCapturedSymbols() {
        return capturedSymbols;
    }

    // to determine whether symbols are captured or defined
    private Map<String, Symbol> lambdaSymbols = new TreeMap<>();

    private Map<String, Symbol> forbiddenSymbols = new TreeMap<>();

    private Map<String, Symbol> capturedSymbols = new TreeMap<>();

    public Map<String, Integer> lambdaOffset = new HashMap<>();

    public Map<String, Tree.Expr> symbolExpr = new HashMap<>();
}
