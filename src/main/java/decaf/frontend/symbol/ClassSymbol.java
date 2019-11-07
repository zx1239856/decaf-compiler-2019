package decaf.frontend.symbol;

import decaf.frontend.scope.ClassScope;
import decaf.frontend.scope.GlobalScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.type.ClassType;
import decaf.lowlevel.tac.ClassInfo;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class symbol, representing a class definition.
 */
public final class ClassSymbol extends Symbol {

    public final Optional<ClassSymbol> parentSymbol;

    public final ClassType type;

    public Set<MethodSymbol> non_overridden_methods;
    /**
     * Associated class scope of this class.
     */
    public final ClassScope scope;

    public ClassSymbol(String name, ClassType type, ClassScope scope, Pos pos) {
        super(name, type, pos);
        this.parentSymbol = Optional.empty();
        this.scope = scope;
        this.type = type;
        scope.setOwner(this);
    }

    public ClassSymbol(String name, ClassSymbol parentSymbol, ClassType type, ClassScope scope, Pos pos) {
        super(name, type, pos);
        this.parentSymbol = Optional.of(parentSymbol);
        this.scope = scope;
        this.type = type;
        scope.setOwner(this);
    }

    @Override
    public GlobalScope domain() {
        return (GlobalScope) definedIn;
    }

    @Override
    public boolean isClassSymbol() {
        return true;
    }

    /**
     * Set as main class, by {@link decaf.frontend.typecheck.Namer}.
     */
    public void setMainClass() {
        main = true;
    }

    public void setAbstract() { is_abstract = true; }

    /**
     * Is it a main function?
     *
     * @return true/false
     */
    public boolean isMainClass() {
        return main;
    }

    public boolean isAbstract() { return is_abstract; }

    @Override
    protected String str() {
        return (is_abstract ? "ABSTRACT " : "") + "class " + name + parentSymbol.map(classSymbol -> " : " + classSymbol.name).orElse("");
    }

    /**
     * Get class info, required by tac generation.
     *
     * @return class info
     * @see decaf.lowlevel.tac.ClassInfo
     */
    public ClassInfo getInfo() {
        var memberVariables = new TreeSet<String>();
        var memberMethods = new TreeSet<String>();
        var staticMethods = new TreeSet<String>();

        for (var symbol : scope) {
            if (symbol.isVarSymbol()) {
                memberVariables.add(symbol.name);
            } else if (symbol.isMethodSymbol()) {
                var methodSymbol = (MethodSymbol) symbol;
                if (methodSymbol.isStatic()) {
                    staticMethods.add(methodSymbol.name);
                } else {
                    memberMethods.add(methodSymbol.name);
                }
            }
        }

        return new ClassInfo(name, parentSymbol.map(symbol -> symbol.name), memberVariables, memberMethods,
                staticMethods, isMainClass());
    }

    private boolean main;
    private boolean is_abstract;
}
