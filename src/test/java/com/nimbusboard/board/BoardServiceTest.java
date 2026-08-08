package com.nimbusboard.board;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.dto.BoardDto;
import com.nimbusboard.board.dto.BoardSummaryDto;
import com.nimbusboard.board.dto.UpdateBoardRequest;
import com.nimbusboard.board.models.Board;
import com.nimbusboard.board.models.BoardObject;
import com.nimbusboard.template.TemplateService;
import com.nimbusboard.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.nimbusboard.util.ApiException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private BoardObjectRepository boardObjectRepository;

    @Mock
    private BoardShareService boardShareService;

    @Mock
    private TemplateService templateService;

    @InjectMocks
    private BoardService boardService;

    private User testUser;
    private Board testBoard;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@nimbus.com")
                .name("Test User")
                .role("USER")
                .build();

        testBoard = Board.builder()
                .id(UUID.randomUUID())
                .title("Test Board")
                .ownerId(testUser.getId())
                .objects(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void getUserBoards_returnsListOfBoards() {
        when(boardRepository.findAccessibleByUserId(testUser.getId()))
                .thenReturn(List.of(testBoard));

        List<BoardSummaryDto> boards = boardService.getUserBoards(testUser.getId());

        assertThat(boards).hasSize(1);
        assertThat(boards.get(0).getTitle()).isEqualTo("Test Board");
        verify(boardRepository).findAccessibleByUserId(testUser.getId());
    }

    @Test
    void createBoard_savesAndReturnsBoard() {
        when(boardRepository.save(any(Board.class))).thenReturn(testBoard);

        BoardDto result = boardService.createBoard("Test Board", testUser);

        assertThat(result.getTitle()).isEqualTo("Test Board");
        assertThat(result.getOwnerId()).isEqualTo(testUser.getId().toString());
        verify(boardRepository).save(any(Board.class));
    }

    @Test
    void createBoard_withTemplate_seedsObjectsWithFreshUuids() {
        when(boardRepository.save(any(Board.class))).thenAnswer(invocation -> {
            Board saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        when(templateService.resolveObjects("kanban")).thenReturn(List.of(
                new TemplateService.TemplateObject("sticky", Map.of("x", 60, "y", 164, "text", "Card")),
                new TemplateService.TemplateObject("rect", Map.of("x", 60, "y", 100, "text", "To do"))));

        BoardDto result = boardService.createBoard("From template", testUser, "kanban");

        assertThat(result.getObjects()).hasSize(2);

        ArgumentCaptor<Board> boardCaptor = ArgumentCaptor.forClass(Board.class);
        verify(boardRepository).save(boardCaptor.capture());
        assertThat(boardCaptor.getValue().getTemplateSlug()).isEqualTo("kanban");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BoardObject>> objectCaptor = ArgumentCaptor.forClass(List.class);
        verify(boardObjectRepository).saveAll(objectCaptor.capture());

        List<BoardObject> seeded = objectCaptor.getValue();
        assertThat(seeded).hasSize(2);
        assertThat(seeded).extracting(BoardObject::getId).doesNotHaveDuplicates();
        // Ids must be server-generated: board_objects.id is a global key, so authored ids would collide.
        assertThat(seeded).allSatisfy(obj -> {
            assertThatCode(() -> UUID.fromString(obj.getId())).doesNotThrowAnyException();
            assertThat(obj.getCreatedBy()).isEqualTo(testUser.getId());
        });
        assertThat(seeded).extracting(BoardObject::getType).containsExactly("sticky", "rect");
    }

    @Test
    void createBoard_unknownTemplate_failsBeforeWritingAnything() {
        when(templateService.resolveObjects("does-not-exist"))
                .thenThrow(new ApiException("Template not found: does-not-exist", HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> boardService.createBoard("Doomed", testUser, "does-not-exist"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Template not found: does-not-exist");

        verify(boardRepository, never()).save(any(Board.class));
        verify(boardObjectRepository, never()).saveAll(any());
    }

    @Test
    void createBoard_blankTemplateSlug_staysAnEmptyBoard() {
        when(boardRepository.save(any(Board.class))).thenReturn(testBoard);

        BoardDto result = boardService.createBoard("Blank", testUser, "   ");

        assertThat(result.getObjects()).isEmpty();
        verifyNoInteractions(templateService);

        ArgumentCaptor<Board> boardCaptor = ArgumentCaptor.forClass(Board.class);
        verify(boardRepository).save(boardCaptor.capture());
        assertThat(boardCaptor.getValue().getTemplateSlug()).isNull();
    }

    @Test
    void getBoardById_existingBoard_returnsBoard() {
        when(boardRepository.findById(testBoard.getId())).thenReturn(Optional.of(testBoard));
        when(boardShareService.resolveAccess(testBoard, testUser.getId()))
                .thenReturn(BoardShareService.AccessLevel.OWNER);

        BoardDto result = boardService.getBoardById(testBoard.getId(), testUser);

        assertThat(result.getId()).isEqualTo(testBoard.getId().toString());
        assertThat(result.getTitle()).isEqualTo("Test Board");
        assertThat(result.getCurrentUserRole()).isEqualTo("OWNER");
        verify(boardShareService).requireViewAccess(testBoard, testUser);
    }

    @Test
    void getBoardById_nonExistingBoard_throwsException() {
        UUID randomId = UUID.randomUUID();
        when(boardRepository.findById(randomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.getBoardById(randomId, testUser))
                .isInstanceOf(ApiException.class)
                .hasMessage("Board not found");
    }

    @Test
    void updateBoard_asOwner_updatesTitle() {
        UpdateBoardRequest request = new UpdateBoardRequest();
        request.setTitle("Updated Title");

        when(boardRepository.findById(testBoard.getId())).thenReturn(Optional.of(testBoard));
        when(boardRepository.save(any(Board.class))).thenReturn(testBoard);

        BoardDto result = boardService.updateBoard(testBoard.getId(), request, testUser);

        assertThat(result).isNotNull();
        verify(boardRepository).save(any(Board.class));
    }

    @Test
    void updateBoard_asNonOwner_throwsForbidden() {
        User otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@nimbus.com")
                .name("Other")
                .role("USER")
                .build();

        UpdateBoardRequest request = new UpdateBoardRequest();
        request.setTitle("Hacked");

        when(boardRepository.findById(testBoard.getId())).thenReturn(Optional.of(testBoard));
        doThrow(new ApiException("Only the board owner can perform this action", HttpStatus.FORBIDDEN))
                .when(boardShareService).requireOwner(testBoard, otherUser);

        assertThatThrownBy(() -> boardService.updateBoard(testBoard.getId(), request, otherUser))
                .isInstanceOf(ApiException.class)
                .hasMessage("Only the board owner can perform this action");
    }

    @Test
    void deleteBoard_asOwner_deletesSuccessfully() {
        when(boardRepository.findById(testBoard.getId())).thenReturn(Optional.of(testBoard));

        boardService.deleteBoard(testBoard.getId(), testUser);

        verify(boardRepository).delete(testBoard);
    }

    @Test
    void deleteBoard_asNonOwner_throwsForbidden() {
        User otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@nimbus.com")
                .name("Other")
                .role("USER")
                .build();

        when(boardRepository.findById(testBoard.getId())).thenReturn(Optional.of(testBoard));
        doThrow(new ApiException("Only the board owner can perform this action", HttpStatus.FORBIDDEN))
                .when(boardShareService).requireOwner(testBoard, otherUser);

        assertThatThrownBy(() -> boardService.deleteBoard(testBoard.getId(), otherUser))
                .isInstanceOf(ApiException.class)
                .hasMessage("Only the board owner can perform this action");
    }
}
