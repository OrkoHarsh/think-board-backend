package com.nimbusboard.template;

import com.nimbusboard.template.models.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    List<Template> findByActiveTrueOrderBySortOrderAsc();

    Optional<Template> findBySlugAndActiveTrue(String slug);
}
