package com.nimbusboard.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kinds of diagram Ask ThinkBoard can produce.
 *
 * <p>{@link #HLD} is the default and keeps the original architecture behaviour. A request only
 * moves off it when the caller asks explicitly or the prompt names another diagram outright, so
 * existing architecture prompts are unaffected.
 */
public enum DiagramType {
    HLD,
    FLOWCHART,
    CLASS;

    private static final Pattern CLASS_HINT = Pattern.compile(
            "\\b(class\\s+diagram|uml\\s+(class\\s+)?diagram|class\\s+model|domain\\s+model|"
                    + "entity\\s+model|object\\s+model|er\\s+diagram|schema\\s+diagram)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FLOWCHART_HINT = Pattern.compile(
            "\\b(flow\\s*chart|flow\\s+diagram|process\\s+flow|workflow|work\\s+flow|user\\s+flow|"
                    + "decision\\s+tree|sequence\\s+of\\s+steps|step\\s+by\\s+step\\s+flow|"
                    + "state\\s+machine|onboarding\\s+flow|checkout\\s+flow)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Parses the client-supplied value, falling back to {@code null} when absent or unknown. */
    public static DiagramType fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (DiagramType type : values()) {
            if (type.name().equals(normalized)) return type;
        }
        if (normalized.equals("FLOW") || normalized.equals("FLOW_CHART")) return FLOWCHART;
        if (normalized.equals("UML") || normalized.equals("UML_CLASS")) return CLASS;
        if (normalized.equals("ARCHITECTURE") || normalized.equals("SYSTEM_DESIGN")) return HLD;
        return null;
    }

    /** Explicit selection wins; otherwise infer from unmistakable wording, defaulting to HLD. */
    public static DiagramType resolve(String requested, String prompt) {
        DiagramType explicit = fromValue(requested);
        if (explicit != null) return explicit;
        if (prompt == null || prompt.isBlank()) return HLD;
        if (CLASS_HINT.matcher(prompt).find()) return CLASS;
        if (FLOWCHART_HINT.matcher(prompt).find()) return FLOWCHART;
        return HLD;
    }

    public String label() {
        return switch (this) {
            case HLD -> "architecture (HLD)";
            case FLOWCHART -> "flowchart";
            case CLASS -> "UML class diagram";
        };
    }
}
