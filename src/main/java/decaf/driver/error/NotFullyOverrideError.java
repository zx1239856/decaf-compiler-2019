package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class NotFullyOverrideError extends DecafError {

    private String class_name;

    public NotFullyOverrideError(String class_name, Pos pos) {
        super(pos);
        this.class_name = class_name;
    }

    @Override
    protected String getErrMsg() {
        return "'" + class_name + "' is not abstract and does not override all abstract methods";
    }

}
