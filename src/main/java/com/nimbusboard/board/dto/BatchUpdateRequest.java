package com.nimbusboard.board.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BatchUpdateRequest {
    @NotEmpty(message = "Objects list cannot be empty")
    private List<ObjectUpdate> objects;

    @Data
    public static class ObjectUpdate {
        private String id;
        private String type;
        private Map<String, Object> properties;
        private Long version; // for optimistic locking
    }
}
