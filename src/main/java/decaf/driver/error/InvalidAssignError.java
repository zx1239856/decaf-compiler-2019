package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class InvalidAssignError extends DecafError {
    public InvalidAssignError(Pos pos, String name, boolean isClassMember) {
        super(pos);
        this.name = name;
        this.isClassMember = isClassMember;
    }

    @Override
    protected String getErrMsg() {
        return "cannot assign value to " + (isClassMember ? ("class member method '" + this.name + "'") : "captured variables in lambda expression");
    }

    private String name;
    private Boolean isClassMember;
}
