package com.nimbusboard.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiGenerateRequest {
    @NotBlank(message = "Board ID is required")
    private String boardId;

    @NotBlank(message = "Prompt is required")
    private String prompt;
}
