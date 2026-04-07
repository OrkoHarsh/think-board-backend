package com.nimbusboard.board;

import com.nimbusboard.board.models.BoardObject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BoardObjectRepository extends JpaRepository<BoardObject, String> {
    List<BoardObject> findByBoardId(UUID boardId);
    void deleteByBoardId(UUID boardId);
}
