package com.nimbusboard.template.dto;

import com.nimbusboard.board.dto.BoardObjectDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TemplateDto {
    private String slug;
    private String name;
    private String description;
    private String category;

    /**
     * Flattened objects for rendering a live preview in the picker. These carry synthetic ids and are
     * never persisted; the server generates fresh ids when a template is applied.
     */
    private List<BoardObjectDto> objects;
}
