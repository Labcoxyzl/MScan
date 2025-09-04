package fdu.secsys.microservice.enums;

/**
 * enum class for sql query type
 */
public enum QueryType {
    SELECT("SELECT"),
    INSERT("INSERT"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
    UNKNOWN("UNKNOWN");

    private final String value;

    QueryType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
