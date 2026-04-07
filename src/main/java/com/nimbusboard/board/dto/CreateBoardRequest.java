package com.nimbusboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateBoardRequest {
    @NotBlank(message = "Title is required")
    private String title;
}
