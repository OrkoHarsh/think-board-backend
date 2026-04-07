package com.nimbusboard.board;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.dto.BoardDto;
import com.nimbusboard.board.dto.BoardSummaryDto;
import com.nimbusboard.board.dto.UpdateBoardRequest;
import com.nimbusboard.board.models.Board;
import com.nimbusboard.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private BoardObjectRepository boardObjectRepository;

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
        when(boardRepository.findByOwnerIdOrderByUpdatedAtDesc(testUser.getId()))
                .thenReturn(List.of(testBoard));

        List<BoardSummaryDto> boards = boardService.getUserBoards(testUser.getId());

        assertThat(boards).hasSize(1);
        assertThat(boards.get(0).getTitle()).isEqualTo("Test Board");
        verify(boardRepository).findByOwnerIdOrderByUpdatedAtDesc(testUser.getId());
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
    void getBoardById_existingBoard_returnsBoard() {
        when(boardRepository.findById(testBoard.getId())).thenReturn(Optional.of(testBoard));

        BoardDto result = boardService.getBoardById(testBoard.getId(), testUser);

        assertThat(result.getId()).isEqualTo(testBoard.getId().toString());
        assertThat(result.getTitle()).isEqualTo("Test Board");
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

        assertThatThrownBy(() -> boardService.updateBoard(testBoard.getId(), request, otherUser))
                .isInstanceOf(ApiException.class)
                .hasMessage("Not authorized to update this board");
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

        assertThatThrownBy(() -> boardService.deleteBoard(testBoard.getId(), otherUser))
                .isInstanceOf(ApiException.class)
                .hasMessage("Not authorized to delete this board");
    }
}
