package decaf.driver.error;

import decaf.frontend.tree.Pos;


import decaf.frontend.tree.Pos;

/**
 * Can not instantiate abstract class
 */
public class AbstractNewError extends DecafError {

    private String class_name;

    public AbstractNewError(String class_name, Pos pos) {
        super(pos);
        this.class_name = class_name;
    }

    @Override
    protected String getErrMsg() {
        return "cannot instantiate abstract class '" + class_name + "'";
    }

}