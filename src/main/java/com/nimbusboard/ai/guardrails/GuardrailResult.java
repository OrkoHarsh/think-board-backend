package com.nimbusboard.ai.guardrails;

/**
 * Outcome of a guardrail check.
 *
 * @param category why the check failed, or {@link GuardrailCategory#ALLOWED}
 * @param message  user-facing explanation; safe to surface in the UI
 * @param sanitized the normalized prompt to send downstream (only set when allowed)
 */
public record GuardrailResult(GuardrailCategory category, String message, String sanitized) {

    public boolean allowed() {
        return category == GuardrailCategory.ALLOWED;
    }

    public static GuardrailResult allow(String sanitized) {
        return new GuardrailResult(GuardrailCategory.ALLOWED, null, sanitized);
    }

    public static GuardrailResult block(GuardrailCategory category, String message) {
        return new GuardrailResult(category, message, null);
    }
}
