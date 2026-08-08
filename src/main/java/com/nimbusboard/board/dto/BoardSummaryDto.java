package com.nimbusboard.board.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BoardSummaryDto {
    private String id;
    private String title;
    private Instant updatedAt;
    /** Lightweight sample of canvas objects for dashboard thumbnails. */
    private List<BoardObjectDto> previewObjects;
}