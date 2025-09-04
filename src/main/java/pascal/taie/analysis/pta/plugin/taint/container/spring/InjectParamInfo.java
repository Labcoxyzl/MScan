package pascal.taie.analysis.pta.plugin.taint.container.spring;

public class InjectParamInfo {
    private boolean byType;
    private boolean request;
    private String qualifier;
    private String type;
    private int index;

    public InjectParamInfo(boolean byType, boolean request, String qualifier, String type, int index) {
        this.byType = byType;
        this.request = request;
        this.qualifier = qualifier;
        this.type = type;
        this.index = index;
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

    public String getType() {
        return type;
    }

    public int getIndex() {
        return index;
    }
}
