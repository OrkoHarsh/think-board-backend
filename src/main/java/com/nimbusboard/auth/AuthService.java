package com.nimbusboard.auth;

import com.nimbusboard.auth.dto.*;
import com.nimbusboard.auth.models.RefreshToken;
import com.nimbusboard.auth.models.RefreshTokenRepository;
import com.nimbusboard.auth.models.User;
import com.nimbusboard.auth.models.UserRepository;
import com.nimbusboard.util.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

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

        // Create refresh token
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

        // Revoke old, issue new
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

    private UserDto toUserDto(User user) {
        return UserDto.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
