package decaf.frontend.scope;

import decaf.frontend.tree.Tree;

import java.util.ArrayList;
import java.util.List;

public class LambdaScope extends FormalScope{
    public List<Tree.Return> returnStmtList = new ArrayList<>(); // for return type deduction

    public LambdaScope(Scope parent) {
        super();
        assert parent.isLocalScope();
        var par = (LocalScope)parent;
        par.nestedScopes.add(this);
    }

    @Override
    public boolean isLambdaScope() {
        return true;
    }
}
