package com.nimbusboard.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiGenerateResponse {
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
