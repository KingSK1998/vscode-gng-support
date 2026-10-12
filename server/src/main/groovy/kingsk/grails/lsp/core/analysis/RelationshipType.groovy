package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
enum RelationshipType {
    DOMAIN_ASSOCIATION("DOMAIN_ASSOCIATION"),
    INJECTION("INJECTION"),
    ROUTE_TO_ACTION("ROUTE_TO_ACTION"),
    RENDERS_VIEW("RENDERS_VIEW"),
    TAG_CALL("TAG_CALL"),
    CONFIG_USAGE("CONFIG_USAGE"),
    EVENT_LISTENER("EVENT_LISTENER")

    final String value

    RelationshipType(String value) {
        this.value = value
    }

    static RelationshipType fromString(String val) {
        if (!val) return null
        for (RelationshipType t : values()) {
            if (t.value.equalsIgnoreCase(val) || t.name().equalsIgnoreCase(val)) {
                return t
            }
        }
        return null
    }
}
