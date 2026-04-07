package com.nimbusboard.board.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BoardObjectDto {
    private String id;
    private String type;
    private Map<String, Object> properties;
    private Long version;
    private Instant updatedAt;

    // Flattened fields that the React frontend expects on the object root.
    // These are merged from properties for convenience.
    private Double x;
    private Double y;
    private Double width;
    private Double height;
    private String fill;
    private String text;
    private String stroke;
    private Double strokeWidth;
    private Object points;
}
