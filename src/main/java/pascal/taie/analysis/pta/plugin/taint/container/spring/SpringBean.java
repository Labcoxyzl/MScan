package pascal.taie.analysis.pta.plugin.taint.container.spring;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.Var;

import java.util.List;
import java.util.Set;

public class SpringBean {
    private Set<String> name;
    private boolean primary;
    private Obj obj;
    List<Var> retVars;

    public SpringBean(Set<String> name, boolean isPrimary, Obj obj) {
        this.name = name;
        this.primary = isPrimary;
        this.obj = obj;
    }

    public SpringBean(Set<String> name, boolean primary, Obj obj, List<Var> retVars) {
        this.name = name;
        this.primary = primary;
        this.obj = obj;
        this.retVars = retVars;
    }

    public Set<String> getName() {
        return name;
    }

    public boolean isPrimary() {
        return primary;
    }

    public Obj getObj() {
        return obj;
    }

    public List<Var> getRetVars() {
        return retVars;
    }
}
