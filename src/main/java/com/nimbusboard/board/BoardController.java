package com.nimbusboard.board;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.dto.*;
import com.nimbusboard.util.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BoardSummaryDto>>> getBoards(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(boardService.getUserBoards(user.getId())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BoardDto>> createBoard(
            @Valid @RequestBody CreateBoardRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(boardService.createBoard(request.getTitle(), user)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BoardDto>> getBoard(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(boardService.getBoardById(id, user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BoardDto>> updateBoard(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBoardRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(boardService.updateBoard(id, request, user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteBoard(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        boardService.deleteBoard(id, user);
        return ResponseEntity.ok(ApiResponse.success("Board deleted"));
    }

    @PostMapping("/{id}/objects/batch")
    public ResponseEntity<ApiResponse<List<BoardObjectDto>>> batchUpdate(
            @PathVariable UUID id,
            @Valid @RequestBody BatchUpdateRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                boardService.batchUpdateObjects(id, request, user)));
    }
}
