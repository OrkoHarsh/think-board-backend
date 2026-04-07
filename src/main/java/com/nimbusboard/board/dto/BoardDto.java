package com.nimbusboard.board.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class BoardDto {
    private String id;
    private String title;
    private String ownerId;
    private Instant createdAt;
    private Instant updatedAt;
    private List<BoardObjectDto> objects;
}
