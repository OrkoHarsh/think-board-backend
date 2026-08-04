package com.nimbusboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShareBoardRequest {
    /** Registered user's display name or email */
    @NotBlank(message = "Username is required")
    private String username;

    /** VIEW = view only, EDIT = view + edit */
    @NotBlank(message = "Role is required")
    private String role;
}
