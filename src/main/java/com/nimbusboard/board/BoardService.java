package com.nimbusboard.board;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.dto.*;
import com.nimbusboard.board.models.Board;
import com.nimbusboard.board.models.BoardObject;
import com.nimbusboard.template.TemplateService;
import com.nimbusboard.util.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final BoardObjectRepository boardObjectRepository;
    private final BoardShareService boardShareService;
    private final TemplateService templateService;

    @Transactional(readOnly = true)
    public List<BoardSummaryDto> getUserBoards(UUID userId) {
        return boardRepository.findAccessibleByUserId(userId).stream()
                .map(this::toBoardSummary)
                .collect(Collectors.toList());
    }

    @Transactional
    public BoardDto createBoard(String title, User user) {
        return createBoard(title, user, null);
    }

    /**
     * Creates a board, optionally pre-populated from a template.
     *
     * Template objects always get server-generated ids: {@code board_objects.id} is a global primary key,
     * so reusing authored ids would collide with objects on other boards.
     */
    @Transactional
    public BoardDto createBoard(String title, User user, String templateSlug) {
        String slug = (templateSlug == null || templateSlug.isBlank()) ? null : templateSlug.trim();

        // Resolve the template first: an unknown slug then fails before anything is written,
        // rather than leaving the rollback to undo a board insert.
        List<TemplateService.TemplateObject> templateObjects =
                slug != null ? templateService.resolveObjects(slug) : List.of();

        Board board = Board.builder()
                .title(title)
                .ownerId(user.getId())
                .templateSlug(slug)
                .build();
        board = boardRepository.save(board);

        if (!templateObjects.isEmpty()) {
            List<BoardObject> seeded = new ArrayList<>(templateObjects.size());
            for (TemplateService.TemplateObject obj : templateObjects) {
                seeded.add(BoardObject.builder()
                        .id(UUID.randomUUID().toString())
                        .board(board)
                        .type(obj.type())
                        .properties(new HashMap<>(obj.properties()))
                        .createdBy(user.getId())
                        .build());
            }
            boardObjectRepository.saveAll(seeded);
            board.getObjects().addAll(seeded);
            log.info("Board {} seeded with {} objects from template {}", board.getId(), seeded.size(), slug);
        }

        log.info("Board created: {} by user {}", board.getId(), user.getEmail());
        return toBoardDto(board);
    }

    @Transactional(readOnly = true)
    public BoardDto getBoardById(UUID boardId, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));
        boardShareService.requireViewAccess(board, user);
        BoardDto dto = toBoardDto(board);
        BoardShareService.AccessLevel level = boardShareService.resolveAccess(board, user.getId());
        dto.setCurrentUserRole(level.name());
        return dto;
    }

    @Transactional
    public BoardDto updateBoard(UUID boardId, UpdateBoardRequest request, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

        boardShareService.requireOwner(board, user);

        board.setTitle(request.getTitle());
        board = boardRepository.save(board);
        return toBoardDto(board);
    }

    @Transactional
    public void deleteBoard(UUID boardId, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

        boardShareService.requireOwner(board, user);

        log.info("AUDIT: Board {} deleted by user {}", boardId, user.getEmail());
        boardRepository.delete(board);
    }

    @Transactional
    public List<BoardObjectDto> batchUpdateObjects(UUID boardId, BatchUpdateRequest request, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));
        boardShareService.requireEditAccess(board, user);

        List<BoardObjectDto> results = new ArrayList<>();

        for (BatchUpdateRequest.ObjectUpdate update : request.getObjects()) {
            try {
                BoardObject obj = boardObjectRepository.findById(update.getId()).orElse(null);

                if (obj == null) {
                    // Create new
                    obj = BoardObject.builder()
                            .id(update.getId())
                            .board(board)
                            .type(update.getType())
                            .properties(update.getProperties() != null ? update.getProperties() : new HashMap<>())
                            .createdBy(user.getId())
                            .build();
                } else {
                    // Check version for optimistic lock
                    if (update.getVersion() != null && !update.getVersion().equals(obj.getVersion())) {
                        throw new ObjectOptimisticLockingFailureException(BoardObject.class.getName(), update.getId());
                    }
                    if (update.getType() != null) obj.setType(update.getType());
                    if (update.getProperties() != null) obj.getProperties().putAll(update.getProperties());
                }

                obj = boardObjectRepository.save(obj);
                results.add(toObjectDto(obj));
            } catch (ObjectOptimisticLockingFailureException e) {
                throw new ApiException(
                        "Version conflict on object " + update.getId() + ". Refresh and retry.",
                        HttpStatus.CONFLICT);
            }
        }

        touchBoard(board);
        return results;
    }

    /** Bump board.updatedAt so dashboard "last updated" reflects canvas edits. */
    @Transactional
    public void touchBoard(UUID boardId) {
        boardRepository.findById(boardId).ifPresent(this::touchBoard);
    }

    private void touchBoard(Board board) {
        board.setUpdatedAt(Instant.now());
        boardRepository.save(board);
    }

    // --- Object-level CRUD used by realtime service ---

    @Transactional
    public BoardObject createObject(UUID boardId, String objectId, String type,
                                     Map<String, Object> properties, UUID userId) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

        BoardObject obj = BoardObject.builder()
                .id(objectId)
                .board(board)
                .type(type)
                .properties(properties != null ? properties : new HashMap<>())
                .createdBy(userId)
                .build();

        obj = boardObjectRepository.save(obj);
        touchBoard(board);
        return obj;
    }

    @Transactional
    public BoardObject updateObject(String objectId, Map<String, Object> updates) {
        BoardObject obj = boardObjectRepository.findById(objectId).orElse(null);
        if (obj == null) return null;
        if (updates != null) {
            obj.getProperties().putAll(updates);
        }
        obj = boardObjectRepository.save(obj);
        if (obj.getBoard() != null) {
            touchBoard(obj.getBoard());
        }
        return obj;
    }

    @Transactional
    public void deleteObject(String objectId) {
        BoardObject obj = boardObjectRepository.findById(objectId).orElse(null);
        if (obj == null) return;
        Board board = obj.getBoard();
        boardObjectRepository.delete(obj);
        if (board != null) {
            touchBoard(board);
        }
    }

    // --- Mapping ---

    private BoardDto toBoardDto(Board board) {
        List<BoardObjectDto> objectDtos = board.getObjects().stream()
                .map(this::toObjectDto)
                .collect(Collectors.toList());

        return BoardDto.builder()
                .id(board.getId().toString())
                .title(board.getTitle())
                .ownerId(board.getOwnerId().toString())
                .createdAt(board.getCreatedAt())
                .updatedAt(board.getUpdatedAt())
                .objects(objectDtos)
                .build();
    }

    private static final int PREVIEW_OBJECT_LIMIT = 32;

    private BoardSummaryDto toBoardSummary(Board board) {
        List<BoardObjectDto> preview = board.getObjects() == null
                ? List.of()
                : board.getObjects().stream()
                        .limit(PREVIEW_OBJECT_LIMIT)
                        .map(this::toObjectDto)
                        .collect(Collectors.toList());

        return BoardSummaryDto.builder()
                .id(board.getId().toString())
                .title(board.getTitle())
                .updatedAt(board.getUpdatedAt())
                .previewObjects(preview)
                .build();
    }

    BoardObjectDto toObjectDto(BoardObject obj) {
        BoardObjectDto dto = BoardObjectDto.flatten(obj.getId(), obj.getType(), obj.getProperties());
        dto.setVersion(obj.getVersion());
        dto.setUpdatedAt(obj.getUpdatedAt());
        return dto;
    }
}
