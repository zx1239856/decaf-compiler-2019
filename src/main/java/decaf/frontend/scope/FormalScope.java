package decaf.frontend.scope;

import decaf.frontend.symbol.MethodSymbol;

import java.util.Optional;

/**
 * Formal scope: stores parameter variable symbols. It is owned by a method symbol.
 */
public class FormalScope extends Scope {

    public FormalScope() {
        super(Kind.FORMAL);
    }

    public MethodSymbol getOwner() {
        return owner;
    }

    public void setOwner(MethodSymbol owner) {
        this.owner = owner;
    }

    @Override
    public boolean isFormalScope() {
        return true;
    }

    /**
     * Get the local scope associated with the method body.
     *
     * @return local scope
     */
    public Optional<LocalScope> nestedLocalScope() {
        return Optional.ofNullable(nested);
    }

    /**
     * Set the local scope.
     *
     * @param scope local scope
     */
    void setNested(LocalScope scope) {
        nested = scope;
    }

    private MethodSymbol owner;

    private LocalScope nested;
}
