package com.nimbusboard.board.models;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "board_objects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardObject {

    @Id
    private String id; // Frontend generates UUIDs as strings

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @Column(nullable = false)
    private String type; // shape, text, sticky, line, arrow, freehand, rect, circle, etc.

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    @Version
    private Long version;

    @Column(name = "created_by")
    private UUID createdBy;

    @UpdateTimestamp
    private Instant updatedAt;
}
