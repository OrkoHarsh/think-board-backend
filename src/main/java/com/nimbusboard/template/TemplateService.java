package com.nimbusboard.template;

import com.nimbusboard.board.dto.BoardObjectDto;
import com.nimbusboard.template.dto.TemplateDto;
import com.nimbusboard.template.models.Template;
import com.nimbusboard.util.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    /** Upper bound on objects per template, so applying one stays a bounded insert. */
    static final int MAX_OBJECTS_PER_TEMPLATE = 100;

    private final TemplateRepository templateRepository;

    @Transactional(readOnly = true)
    public List<TemplateDto> listActive() {
        return templateRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Resolves the objects a template should stamp onto a new board.
     *
     * @throws ApiException 404 when the slug is unknown or the template is inactive.
     */
    @Transactional(readOnly = true)
    public List<TemplateObject> resolveObjects(String slug) {
        Template template = templateRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new ApiException("Template not found: " + slug, HttpStatus.NOT_FOUND));
        return parseObjects(template);
    }

    private TemplateDto toDto(Template template) {
        List<TemplateObject> objects = parseObjects(template);
        List<BoardObjectDto> previews = new ArrayList<>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            TemplateObject obj = objects.get(i);
            previews.add(BoardObjectDto.flatten(template.getSlug() + "-preview-" + i, obj.type(), obj.properties()));
        }

        return TemplateDto.builder()
                .slug(template.getSlug())
                .name(template.getName())
                .description(template.getDescription())
                .category(template.getCategory())
                .objects(previews)
                .build();
    }

    /**
     * Definitions are seeded content, so a malformed one is a deployment bug rather than user error:
     * fail loudly instead of silently creating a half-rendered board.
     */
    private List<TemplateObject> parseObjects(Template template) {
        Map<String, Object> definition = template.getDefinition();
        Object raw = definition != null ? definition.get("objects") : null;

        if (raw == null) return List.of();
        if (!(raw instanceof List<?> entries)) {
            throw malformed(template, "\"objects\" must be an array");
        }
        if (entries.size() > MAX_OBJECTS_PER_TEMPLATE) {
            throw malformed(template, "has " + entries.size() + " objects, limit is " + MAX_OBJECTS_PER_TEMPLATE);
        }

        List<TemplateObject> objects = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw malformed(template, "each object must be a JSON object");
            }

            Object type = map.get("type");
            if (!(type instanceof String typeName) || typeName.isBlank()) {
                throw malformed(template, "each object needs a non-blank \"type\"");
            }

            Object props = map.get("properties");
            Map<String, Object> properties = new HashMap<>();
            if (props instanceof Map<?, ?> propMap) {
                propMap.forEach((k, v) -> properties.put(String.valueOf(k), v));
            } else if (props != null) {
                throw malformed(template, "\"properties\" must be a JSON object");
            }

            objects.add(new TemplateObject(typeName, properties));
        }

        return objects;
    }

    private ApiException malformed(Template template, String detail) {
        log.error("Malformed template definition for slug {}: {}", template.getSlug(), detail);
        return new ApiException(
                "Template " + template.getSlug() + " is misconfigured",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** A single canvas object from a template definition, before it is bound to a board. */
    public record TemplateObject(String type, Map<String, Object> properties) {
    }
}
