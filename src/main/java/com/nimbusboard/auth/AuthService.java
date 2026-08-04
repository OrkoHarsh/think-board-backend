package com.nimbusboard.auth;

import com.nimbusboard.auth.dto.*;
import com.nimbusboard.auth.models.PasswordResetToken;
import com.nimbusboard.auth.models.PasswordResetTokenRepository;
import com.nimbusboard.auth.models.RefreshToken;
import com.nimbusboard.auth.models.RefreshTokenRepository;
import com.nimbusboard.auth.models.User;
import com.nimbusboard.auth.models.UserRepository;
import com.nimbusboard.board.InviteEmailService;
import com.nimbusboard.util.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long RESET_TOKEN_TTL_MS = 3_600_000L; // 1 hour

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final InviteEmailService inviteEmailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException("Email already in use", HttpStatus.CONFLICT);
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("USER")
                .build();

        user = userRepository.save(user);
        log.info("User signed up: {}", user.getEmail());

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .user(toUserDto(user))
                .token(accessToken)
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user;
        try {
            user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new ApiException("Invalid credentials", HttpStatus.UNAUTHORIZED));
        } catch (DataAccessException e) {
            log.error("Database error during login lookup for email: {}", request.getEmail(), e);
            throw new ApiException("Authentication service temporarily unavailable", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole());

        RefreshToken refreshToken = RefreshToken.builder()
                .token(jwtProvider.generateRefreshTokenString())
                .user(user)
                .expiresAt(Instant.now().plusMillis(jwtProvider.getRefreshTokenExpirationMs()))
                .build();

        try {
            refreshTokenRepository.save(refreshToken);
        } catch (DataAccessException e) {
            log.error("Database error saving refresh token for user: {}", user.getEmail(), e);
            throw new ApiException("Authentication service temporarily unavailable", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        log.info("User logged in: {}", user.getEmail());

        return AuthResponse.builder()
                .user(toUserDto(user))
                .token(accessToken)
                .build();
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenStr) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenAndRevokedFalse(refreshTokenStr)
                .orElseThrow(() -> new ApiException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED));

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            refreshToken.setRevoked(true);
            refreshTokenRepository.save(refreshToken);
            throw new ApiException("Refresh token expired", HttpStatus.UNAUTHORIZED);
        }

        User user = refreshToken.getUser();

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .token(jwtProvider.generateRefreshTokenString())
                .user(user)
                .expiresAt(Instant.now().plusMillis(jwtProvider.getRefreshTokenExpirationMs()))
                .build();
        refreshTokenRepository.save(newRefreshToken);

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .user(toUserDto(user))
                .token(accessToken)
                .build();
    }

    public UserDto getCurrentUser(User user) {
        return toUserDto(user);
    }

    /**
     * Always returns the same message to avoid email enumeration.
     */
    @Transactional
    public String forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().trim();
        String generic = "If an account exists for that email, we sent a reset link.";

        userRepository.findByEmail(email).ifPresent(user -> {
            passwordResetTokenRepository.invalidateUnusedForUser(user.getId());

            String rawToken = generateRawToken();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .tokenHash(hashToken(rawToken))
                    .user(user)
                    .expiresAt(Instant.now().plusMillis(RESET_TOKEN_TTL_MS))
                    .build();
            passwordResetTokenRepository.save(resetToken);

            inviteEmailService.sendPasswordReset(user.getEmail(), user.getName(), rawToken);
        });

        return generic;
    }

    @Transactional
    public String resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashAndUsedAtIsNull(hashToken(request.getToken().trim()))
                .orElseThrow(() -> new ApiException("Invalid or expired reset link", HttpStatus.BAD_REQUEST));

        if (resetToken.isExpired()) {
            resetToken.setUsedAt(Instant.now());
            passwordResetTokenRepository.save(resetToken);
            throw new ApiException("Invalid or expired reset link", HttpStatus.BAD_REQUEST);
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(resetToken);
        passwordResetTokenRepository.invalidateUnusedForUser(user.getId());
        refreshTokenRepository.revokeAllByUserId(user.getId());

        log.info("Password reset completed for {}", user.getEmail());
        return "Password updated. You can log in with your new password.";
    }

    @Transactional
    public String changePassword(User user, ChangePasswordRequest request) {
        if (user == null) {
            throw new ApiException("Unauthorized", HttpStatus.UNAUTHORIZED);
        }

        User managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new ApiException("Unauthorized", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getCurrentPassword(), managed.getPassword())) {
            throw new ApiException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        if (passwordEncoder.matches(request.getNewPassword(), managed.getPassword())) {
            throw new ApiException("New password must be different from the current password", HttpStatus.BAD_REQUEST);
        }

        managed.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(managed);
        refreshTokenRepository.revokeAllByUserId(managed.getId());

        log.info("Password changed for {}", managed.getEmail());
        return "Password updated successfully.";
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash reset token", e);
        }
    }

    private UserDto toUserDto(User user) {
        return UserDto.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}

