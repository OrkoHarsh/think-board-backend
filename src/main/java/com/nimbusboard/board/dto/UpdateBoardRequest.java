package com.nimbusboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateBoardRequest {
    @NotBlank(message = "Title is required")
    private String title;
}
