package com.nimbusboard.board.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ShareBoardResponse {
    private BoardMemberDto member;
    private boolean emailSent;
    private String message;
}
