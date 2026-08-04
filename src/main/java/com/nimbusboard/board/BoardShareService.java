package com.nimbusboard.board;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.auth.models.UserRepository;
import com.nimbusboard.board.dto.BoardMemberDto;
import com.nimbusboard.board.dto.ShareBoardRequest;
import com.nimbusboard.board.dto.ShareBoardResponse;
import com.nimbusboard.board.models.Board;
import com.nimbusboard.board.models.BoardMember;
import com.nimbusboard.util.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BoardShareService {

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final UserRepository userRepository;
    private final InviteEmailService inviteEmailService;

    public enum AccessLevel {
        NONE, VIEW, EDIT, OWNER
    }

    public AccessLevel resolveAccess(Board board, UUID userId) {
        if (board.getOwnerId().equals(userId)) {
            return AccessLevel.OWNER;
        }
        return boardMemberRepository.findByBoardIdAndUserId(board.getId(), userId)
                .map(m -> BoardMember.ROLE_EDIT.equalsIgnoreCase(m.getRole())
                        ? AccessLevel.EDIT
                        : AccessLevel.VIEW)
                .orElse(AccessLevel.NONE);
    }

    public void requireViewAccess(Board board, User user) {
        AccessLevel level = resolveAccess(board, user.getId());
        if (level == AccessLevel.NONE) {
            throw new ApiException("Not authorized to view this board", HttpStatus.FORBIDDEN);
        }
    }

    public void requireEditAccess(Board board, User user) {
        AccessLevel level = resolveAccess(board, user.getId());
        if (level != AccessLevel.OWNER && level != AccessLevel.EDIT) {
            throw new ApiException("Not authorized to edit this board", HttpStatus.FORBIDDEN);
        }
    }

    public void requireOwner(Board board, User user) {
        if (!board.getOwnerId().equals(user.getId())) {
            throw new ApiException("Only the board owner can perform this action", HttpStatus.FORBIDDEN);
        }
    }

    public boolean canEdit(UUID boardId, UUID userId) {
        Board board = boardRepository.findById(boardId).orElse(null);
        if (board == null) return false;
        AccessLevel level = resolveAccess(board, userId);
        return level == AccessLevel.OWNER || level == AccessLevel.EDIT;
    }

    public boolean canView(UUID boardId, UUID userId) {
        Board board = boardRepository.findById(boardId).orElse(null);
        if (board == null) return false;
        return resolveAccess(board, userId) != AccessLevel.NONE;
    }

    @Transactional
    public ShareBoardResponse shareBoard(UUID boardId, ShareBoardRequest request, User owner) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));
        requireOwner(board, owner);

        String role = normalizeRole(request.getRole());
        User invitee = resolveInvitee(request.getUsername().trim());

        if (invitee.getId().equals(owner.getId())) {
            throw new ApiException("You already own this board", HttpStatus.BAD_REQUEST);
        }

        BoardMember member = boardMemberRepository.findByBoardIdAndUserId(boardId, invitee.getId())
                .orElse(null);
        if (member == null) {
            member = BoardMember.builder()
                    .boardId(boardId)
                    .userId(invitee.getId())
                    .role(role)
                    .invitedBy(owner.getId())
                    .build();
        } else {
            member.setRole(role);
        }
        member = boardMemberRepository.save(member);

        var emailError = inviteEmailService.sendBoardInvite(
                invitee.getEmail(),
                invitee.getName(),
                board.getTitle(),
                board.getId().toString(),
                role,
                owner.getName()
        );
        boolean emailed = emailError.isEmpty();

        String msg = emailed
                ? "Invite sent to " + invitee.getEmail()
                : "Access granted. Invite email not sent to " + invitee.getEmail() + " (" + emailError.get() + ").";

        return ShareBoardResponse.builder()
                .member(toMemberDto(member, invitee))
                .emailSent(emailed)
                .message(msg)
                .build();
    }

    @Transactional(readOnly = true)
    public List<BoardMemberDto> listMembers(UUID boardId, User requester) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));
        requireViewAccess(board, requester);

        List<BoardMemberDto> result = new ArrayList<>();
        // Owner first
        userRepository.findById(board.getOwnerId()).ifPresent(owner ->
                result.add(BoardMemberDto.builder()
                        .userId(owner.getId().toString())
                        .name(owner.getName())
                        .email(owner.getEmail())
                        .role("OWNER")
                        .createdAt(board.getCreatedAt())
                        .build()));

        for (BoardMember m : boardMemberRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            userRepository.findById(m.getUserId()).ifPresent(u ->
                    result.add(toMemberDto(m, u)));
        }
        return result;
    }

    @Transactional
    public void removeMember(UUID boardId, UUID memberUserId, User owner) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));
        requireOwner(board, owner);

        if (board.getOwnerId().equals(memberUserId)) {
            throw new ApiException("Cannot remove the board owner", HttpStatus.BAD_REQUEST);
        }

        if (!boardMemberRepository.existsByBoardIdAndUserId(boardId, memberUserId)) {
            throw new ApiException("Member not found", HttpStatus.NOT_FOUND);
        }
        boardMemberRepository.deleteByBoardIdAndUserId(boardId, memberUserId);
    }

    private String normalizeRole(String role) {
        if (role == null) {
            throw new ApiException("Role is required", HttpStatus.BAD_REQUEST);
        }
        String r = role.trim().toUpperCase(Locale.ROOT);
        if ("VIEW".equals(r) || "EDIT".equals(r)) {
            return r;
        }
        throw new ApiException("Role must be VIEW or EDIT", HttpStatus.BAD_REQUEST);
    }

    private User resolveInvitee(String usernameOrEmail) {
        if (usernameOrEmail.contains("@")) {
            return userRepository.findByEmail(usernameOrEmail.toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new ApiException(
                            "No registered user found with that email", HttpStatus.NOT_FOUND));
        }

        List<User> matches = userRepository.findByNameIgnoreCase(usernameOrEmail);
        if (matches.isEmpty()) {
            throw new ApiException(
                    "No registered user found with that username. Try their email instead.",
                    HttpStatus.NOT_FOUND);
        }
        if (matches.size() > 1) {
            throw new ApiException(
                    "Multiple users share that name. Enter their registered email instead.",
                    HttpStatus.CONFLICT);
        }
        return matches.get(0);
    }

    private BoardMemberDto toMemberDto(BoardMember member, User user) {
        return BoardMemberDto.builder()
                .userId(user.getId().toString())
                .name(user.getName())
                .email(user.getEmail())
                .role(member.getRole())
                .createdAt(member.getCreatedAt())
                .build();
    }
}
