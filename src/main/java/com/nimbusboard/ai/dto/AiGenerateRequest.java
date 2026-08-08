package com.nimbusboard.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiGenerateRequest {
    @NotBlank(message = "Board ID is required")
    private String boardId;

    @NotBlank(message = "Prompt is required")
    @Size(max = 4000, message = "Prompt is too long")
    private String prompt;

    /** Optional: HLD (default), FLOWCHART or CLASS. Inferred from the prompt when omitted. */
    private String diagramType;
}
