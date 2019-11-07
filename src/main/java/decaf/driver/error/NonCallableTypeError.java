package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class NonCallableTypeError extends DecafError {
    private String name;

    public NonCallableTypeError(String name, Pos pos) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return name + " is not a callable type";
    }
}
