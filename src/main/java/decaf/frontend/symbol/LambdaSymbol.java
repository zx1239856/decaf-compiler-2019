package decaf.frontend.symbol;

import decaf.frontend.scope.FormalScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.FunType;

public class LambdaSymbol extends MethodSymbol {
    public LambdaSymbol(FunType type, FormalScope scope, Pos pos) {
        super("lambda@" + pos.toString(), type, scope, pos, new Tree.Modifiers(), null);
    }

    @Override
    public boolean isMain() {
        return false;
    }

    @Override
    public void setMain() {}
}
