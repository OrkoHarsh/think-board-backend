package com.nimbusboard.board;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.dto.*;
import com.nimbusboard.board.models.Board;
import com.nimbusboard.board.models.BoardObject;
import com.nimbusboard.util.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final BoardObjectRepository boardObjectRepository;

    @Transactional(readOnly = true)
    public List<BoardSummaryDto> getUserBoards(UUID userId) {
        return boardRepository.findByOwnerIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toBoardSummary)
                .collect(Collectors.toList());
    }

    @Transactional
    public BoardDto createBoard(String title, User user) {
        Board board = Board.builder()
                .title(title)
                .ownerId(user.getId())
                .build();
        board = boardRepository.save(board);
        log.info("Board created: {} by user {}", board.getId(), user.getEmail());
        return toBoardDto(board);
    }

    @Transactional(readOnly = true)
    public BoardDto getBoardById(UUID boardId, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));
        // For now, any authenticated user can view; add ACL later
        return toBoardDto(board);
    }

    @Transactional
    public BoardDto updateBoard(UUID boardId, UpdateBoardRequest request, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

        if (!board.getOwnerId().equals(user.getId())) {
            throw new ApiException("Not authorized to update this board", HttpStatus.FORBIDDEN);
        }

        board.setTitle(request.getTitle());
        board = boardRepository.save(board);
        return toBoardDto(board);
    }

    @Transactional
    public void deleteBoard(UUID boardId, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

        if (!board.getOwnerId().equals(user.getId())) {
            throw new ApiException("Not authorized to delete this board", HttpStatus.FORBIDDEN);
        }

        log.info("AUDIT: Board {} deleted by user {}", boardId, user.getEmail());
        boardRepository.delete(board);
    }

    @Transactional
    public List<BoardObjectDto> batchUpdateObjects(UUID boardId, BatchUpdateRequest request, User user) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

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

        return results;
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

        return boardObjectRepository.save(obj);
    }

    @Transactional
    public BoardObject updateObject(String objectId, Map<String, Object> updates) {
        BoardObject obj = boardObjectRepository.findById(objectId).orElse(null);
        if (obj == null) return null;
        if (updates != null) {
            obj.getProperties().putAll(updates);
        }
        return boardObjectRepository.save(obj);
    }

    @Transactional
    public void deleteObject(String objectId) {
        boardObjectRepository.deleteById(objectId);
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

    private BoardSummaryDto toBoardSummary(Board board) {
        return BoardSummaryDto.builder()
                .id(board.getId().toString())
                .title(board.getTitle())
                .updatedAt(board.getUpdatedAt())
                .build();
    }

    BoardObjectDto toObjectDto(BoardObject obj) {
        Map<String, Object> props = obj.getProperties();

        return BoardObjectDto.builder()
                .id(obj.getId())
                .type(obj.getType())
                .properties(props)
                .version(obj.getVersion())
                .updatedAt(obj.getUpdatedAt())
                // Flatten common fields the frontend expects at root level
                .x(toDouble(props.get("x")))
                .y(toDouble(props.get("y")))
                .width(toDouble(props.get("width")))
                .height(toDouble(props.get("height")))
                .fill(props.get("fill") != null ? props.get("fill").toString() : null)
                .text(props.get("text") != null ? props.get("text").toString() : null)
                .stroke(props.get("stroke") != null ? props.get("stroke").toString() : null)
                .strokeWidth(toDouble(props.get("strokeWidth")))
                .points(props.get("points"))
                .build();
    }

    private Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return null; }
    }
}
