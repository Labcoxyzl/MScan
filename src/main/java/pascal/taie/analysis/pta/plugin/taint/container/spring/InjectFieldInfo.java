package pascal.taie.analysis.pta.plugin.taint.container.spring;

public class InjectFieldInfo {
    private boolean byType;
    private boolean request;
    private String qualifier;

    public InjectFieldInfo(boolean byType, boolean request, String qualifier) {
        this.byType = byType;
        this.request = request;
        this.qualifier = qualifier;
    }

    public boolean isByType() {
        return byType;
    }

    public boolean isRequest() {
        return request;
    }

    public String getQualifier() {
        return qualifier;
    }
}
