package decaf.frontend.symbol;

import decaf.frontend.scope.FormalScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.FunType;

public class LambdaSymbol extends MethodSymbol {
    public LambdaSymbol(FunType type, FormalScope scope, Pos pos, boolean parentStatic) {
        super("lambda@" + pos.toString(), type, scope, pos, new Tree.Modifiers(), null);
        this.parentStatic = parentStatic;
    }

    @Override
    public boolean isMain() {
        return false;
    }

    @Override
    public void setMain() {}

    @Override
    public boolean isStatic() {
        return parentStatic;   // whether lambda is in static context depends on its parent
    }

    private boolean parentStatic;
}
