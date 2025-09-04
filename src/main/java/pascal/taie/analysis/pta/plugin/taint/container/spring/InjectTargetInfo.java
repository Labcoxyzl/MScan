package pascal.taie.analysis.pta.plugin.taint.container.spring;

import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;

public class InjectTargetInfo {
    JField jField;

    JClass jClass;

    public InjectTargetInfo(JClass targetClass, JField targetField) {
        this.jClass = targetClass;
        this.jField = targetField;
    }

    public JField getjField() {
        return jField;
    }

    public void setjField(JField jField) {
        this.jField = jField;
    }

    public JClass getjClass() {
        return jClass;
    }

    public void setjClass(JClass jClass) {
        this.jClass = jClass;
    }
}
