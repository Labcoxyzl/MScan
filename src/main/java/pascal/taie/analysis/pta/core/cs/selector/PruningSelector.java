package pascal.taie.analysis.pta.core.cs.selector;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import java.util.Map;

public class PruningSelector extends AbstractContextSelector<Invoke> {

    static int maxLimit = 5;

    static int minLimit = 1;

    static int limit = 2;

    static int hLimit = 1;

    Map<JMethod, String> csMap;

    public PruningSelector(Map<JMethod, String> csMap) {
        this.csMap = csMap;
    }

    @Override
    protected Context selectNewObjContext(CSMethod method, NewObj obj) {
        return factory.makeLastK(method.getContext(), hLimit);
    }

    @Override
    public Context selectContext(CSCallSite callSite, JMethod callee) {
        int selectedLimit = getLimit(callee);
        return factory.append(
                callSite.getContext(), callSite.getCallSite(), selectedLimit);
    }

    @Override
    public Context selectContext(CSCallSite callSite, CSObj recv, JMethod callee) {
        int selectedLimit = getLimit(callee);
        Context parent = callSite.getContext();
        return factory.append(parent, callSite.getCallSite(), selectedLimit);
    }

    private int getLimit(JMethod jMethod) {
        if(csMap.isEmpty()) return minLimit;
        if(!csMap.containsKey(jMethod)) {
            return minLimit;
        } else {
            if(csMap.get(jMethod).equals("MIN")) return minLimit;
            if(csMap.get(jMethod).equals("MAX")) return maxLimit;
            return Integer.parseInt(csMap.get(jMethod));
        }
    }

}
