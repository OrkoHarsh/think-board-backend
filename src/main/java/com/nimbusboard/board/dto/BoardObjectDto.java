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

    /**
     * Builds a DTO from raw canvas properties, lifting the fields the frontend reads at the object root.
     * Shared by persisted board objects and template previews so both render identically.
     */
    public static BoardObjectDto flatten(String id, String type, Map<String, Object> properties) {
        Map<String, Object> props = properties != null ? properties : Map.of();

        return BoardObjectDto.builder()
                .id(id)
                .type(type)
                .properties(props)
                .x(toDouble(props.get("x")))
                .y(toDouble(props.get("y")))
                .width(toDouble(props.get("width")))
                .height(toDouble(props.get("height")))
                .fill(asString(props.get("fill")))
                .text(asString(props.get("text")))
                .stroke(asString(props.get("stroke")))
                .strokeWidth(toDouble(props.get("strokeWidth")))
                .points(props.get("points"))
                .build();
    }

    private static String asString(Object val) {
        return val != null ? val.toString() : null;
    }

    private static Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
