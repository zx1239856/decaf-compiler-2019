package decaf.frontend.scope;

import java.util.ArrayList;
import java.util.List;

/**
 * Local scope: stores locally-defined variables.
 */
public class LocalScope extends Scope {

    public LocalScope(Scope parent) {
        super(Kind.LOCAL);
        assert parent.isFormalOrLocalScope();
        if (parent.isFormalScope()) {
            ((FormalScope) parent).setNested(this);
        } else {
            ((LocalScope) parent).nestedScopes.add(this);
        }
    }

    @Override
    public boolean isLocalScope() {
        return true;
    }

    public List<Scope> nestedScopes = new ArrayList<>();
}
