package com.nimbusboard.ai;

import com.nimbusboard.ai.dto.AiGenerateRequest;
import com.nimbusboard.ai.dto.AiGenerateResponse;
import com.nimbusboard.auth.models.User;
import com.nimbusboard.util.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<AiGenerateResponse>> generate(
            @Valid @RequestBody AiGenerateRequest request,
            @AuthenticationPrincipal User user) {
        AiGenerateResponse response = aiService.generate(
                request.getBoardId(), request.getPrompt(), user);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
