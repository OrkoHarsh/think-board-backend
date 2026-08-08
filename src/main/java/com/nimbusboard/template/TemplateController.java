package com.nimbusboard.template;

import com.nimbusboard.template.dto.TemplateDto;
import com.nimbusboard.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TemplateDto>>> listTemplates() {
        return ResponseEntity.ok(ApiResponse.success(templateService.listActive()));
    }
}
