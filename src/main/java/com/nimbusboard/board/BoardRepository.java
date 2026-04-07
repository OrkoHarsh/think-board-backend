package com.nimbusboard.board;

import com.nimbusboard.board.models.Board;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {
    List<Board> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);
}
