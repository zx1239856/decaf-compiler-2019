package decaf.frontend.scope;

import decaf.frontend.symbol.Symbol;
import decaf.frontend.tree.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    // to determine whether symbols are captured or defined
    private Map<String, Symbol> lambdaSymbols = new TreeMap<>();

    private Map<String, Symbol> forbiddenSymbols = new TreeMap<>();
}
