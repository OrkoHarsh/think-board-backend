package com.nimbusboard.template;

import com.nimbusboard.template.dto.TemplateDto;
import com.nimbusboard.template.models.Template;
import com.nimbusboard.util.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private TemplateRepository templateRepository;

    @InjectMocks
    private TemplateService templateService;

    private Template template(String slug, Map<String, Object> definition) {
        return Template.builder()
                .slug(slug)
                .name("Kanban board")
                .description("Three lanes")
                .category("planning")
                .definition(definition)
                .sortOrder(10)
                .active(true)
                .build();
    }

    private Map<String, Object> definitionOf(Object... objects) {
        return Map.of("schemaVersion", 1, "objects", List.of(objects));
    }

    @Test
    void resolveObjects_returnsTypeAndProperties() {
        Map<String, Object> definition = definitionOf(
                Map.of("type", "sticky", "properties", Map.of("x", 60, "y", 164, "text", "Card")),
                Map.of("type", "arrow", "properties", Map.of("points", List.of(1, 2, 3, 4))));
        when(templateRepository.findBySlugAndActiveTrue("kanban"))
                .thenReturn(Optional.of(template("kanban", definition)));

        List<TemplateService.TemplateObject> objects = templateService.resolveObjects("kanban");

        assertThat(objects).hasSize(2);
        assertThat(objects.get(0).type()).isEqualTo("sticky");
        assertThat(objects.get(0).properties()).containsEntry("text", "Card");
        assertThat(objects.get(1).type()).isEqualTo("arrow");
        assertThat(objects.get(1).properties()).containsKey("points");
    }

    @Test
    void resolveObjects_unknownSlug_throwsNotFound() {
        when(templateRepository.findBySlugAndActiveTrue("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.resolveObjects("ghost"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ghost")
                .extracting(err -> ((ApiException) err).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolveObjects_definitionWithoutObjects_returnsEmpty() {
        when(templateRepository.findBySlugAndActiveTrue("empty"))
                .thenReturn(Optional.of(template("empty", Map.of("schemaVersion", 1))));

        assertThat(templateService.resolveObjects("empty")).isEmpty();
    }

    @Test
    void resolveObjects_objectsNotAnArray_isReportedAsMisconfiguration() {
        when(templateRepository.findBySlugAndActiveTrue("broken"))
                .thenReturn(Optional.of(template("broken", Map.of("objects", "nope"))));

        assertThatThrownBy(() -> templateService.resolveObjects("broken"))
                .isInstanceOf(ApiException.class)
                .extracting(err -> ((ApiException) err).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void resolveObjects_entryMissingType_isReportedAsMisconfiguration() {
        Map<String, Object> definition = definitionOf(Map.of("properties", Map.of("x", 1)));
        when(templateRepository.findBySlugAndActiveTrue("untyped"))
                .thenReturn(Optional.of(template("untyped", definition)));

        assertThatThrownBy(() -> templateService.resolveObjects("untyped"))
                .isInstanceOf(ApiException.class)
                .extracting(err -> ((ApiException) err).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void resolveObjects_overObjectLimit_isRejected() {
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i <= TemplateService.MAX_OBJECTS_PER_TEMPLATE; i++) {
            tooMany.add(Map.of("type", "sticky", "properties", Map.of("x", i)));
        }
        when(templateRepository.findBySlugAndActiveTrue("huge"))
                .thenReturn(Optional.of(template("huge", Map.of("objects", tooMany))));

        assertThatThrownBy(() -> templateService.resolveObjects("huge"))
                .isInstanceOf(ApiException.class)
                .extracting(err -> ((ApiException) err).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void listActive_flattensPreviewFieldsForThumbnails() {
        Map<String, Object> definition = definitionOf(
                Map.of("type", "sticky", "properties", Map.of(
                        "x", 60, "y", 164, "width", 240, "height", 100,
                        "fill", "#DBEAFE", "text", "Card")));
        when(templateRepository.findByActiveTrueOrderBySortOrderAsc())
                .thenReturn(List.of(template("kanban", definition)));

        List<TemplateDto> templates = templateService.listActive();

        assertThat(templates).hasSize(1);
        TemplateDto dto = templates.get(0);
        assertThat(dto.getSlug()).isEqualTo("kanban");
        assertThat(dto.getObjects()).hasSize(1);

        var preview = dto.getObjects().get(0);
        assertThat(preview.getX()).isEqualTo(60.0);
        assertThat(preview.getY()).isEqualTo(164.0);
        assertThat(preview.getWidth()).isEqualTo(240.0);
        assertThat(preview.getFill()).isEqualTo("#DBEAFE");
        assertThat(preview.getText()).isEqualTo("Card");
        assertThat(preview.getId()).isNotBlank();
    }
}
