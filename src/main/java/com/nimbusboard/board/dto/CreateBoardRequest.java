package com.nimbusboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateBoardRequest {
    @NotBlank(message = "Title is required")
    private String title;

    /** Optional starter template; omit or leave blank for an empty board. */
    @Size(max = 100, message = "Template slug is too long")
    private String templateSlug;
}
