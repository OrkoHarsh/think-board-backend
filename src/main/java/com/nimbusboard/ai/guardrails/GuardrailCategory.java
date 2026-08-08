package com.nimbusboard.ai.guardrails;

/** Why a prompt or model output was rejected. Drives the user-facing message and metrics. */
public enum GuardrailCategory {
    ALLOWED,
    EMPTY,
    TOO_LONG,
    PROMPT_INJECTION,
    ILLEGAL_ACTIVITY,
    WEAPONS,
    DRUGS,
    CYBER_ABUSE,
    SELF_HARM,
    SEXUAL_MINORS,
    HATE_HARASSMENT,
    OFF_TOPIC,
    OUTPUT_LEAK,
    OUTPUT_INVALID
}
