package com.nimbusboard.board;

import com.nimbusboard.board.models.BoardMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardMemberRepository extends JpaRepository<BoardMember, UUID> {
    List<BoardMember> findByBoardIdOrderByCreatedAtAsc(UUID boardId);

    List<BoardMember> findByUserId(UUID userId);

    Optional<BoardMember> findByBoardIdAndUserId(UUID boardId, UUID userId);

    boolean existsByBoardIdAndUserId(UUID boardId, UUID userId);

    void deleteByBoardIdAndUserId(UUID boardId, UUID userId);
}
