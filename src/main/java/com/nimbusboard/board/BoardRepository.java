package com.nimbusboard.board;

import com.nimbusboard.board.models.Board;
import com.nimbusboard.board.models.BoardMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {
    List<Board> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    @Query("""
            SELECT DISTINCT b FROM Board b
            LEFT JOIN FETCH b.objects
            WHERE b.ownerId = :userId
               OR b.id IN (SELECT m.boardId FROM BoardMember m WHERE m.userId = :userId)
            ORDER BY b.updatedAt DESC
            """)
    List<Board> findAccessibleByUserId(@Param("userId") UUID userId);
}
