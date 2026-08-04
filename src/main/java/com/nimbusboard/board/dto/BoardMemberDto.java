package com.nimbusboard.board.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
public class BoardMemberDto {
    private String userId;
    private String name;
    private String email;
    private String role;
    private Instant createdAt;
}
