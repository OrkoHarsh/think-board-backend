package com.nimbusboard.board;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class InviteEmailService {

    private static final URI BREVO_SMTP_EMAIL = URI.create("https://api.brevo.com/v3/smtp/email");

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:noreply@thinkboard.local}")
    private String mailFrom;

    @Value("${app.mail.brevo-api-key:}")
    private String brevoApiKey;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    public InviteEmailService(ObjectProvider<JavaMailSender> mailSenderProvider, ObjectMapper objectMapper) {
        this.mailSenderProvider = mailSenderProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * @return empty if sent; otherwise a short reason the email was not sent
     */
    public Optional<String> sendBoardInvite(String toEmail, String toName, String boardTitle,
                                            String boardId, String role, String inviterName) {
        String accessLabel = "EDIT".equalsIgnoreCase(role) ? "view and edit" : "view only";
        String link = frontendUrl.replaceAll("/$", "") + "/board/" + boardId;
        String subject = inviterName + " shared \"" + boardTitle + "\" with you on ThinkBoard";
        String body = """
                Hi %s,

                %s invited you to a ThinkBoard board with %s access.

                Board: %s
                Open link: %s

                Sign in with your registered account to open it.

                — ThinkBoard
                """.formatted(toName, inviterName, accessLabel, boardTitle, link);

        if (!mailEnabled) {
            log.info("BOARD INVITE (email not sent — set MAIL_ENABLED=true): to={} role={} link={}",
                    toEmail, role, link);
            log.info("BOARD INVITE email body:\n{}", body);
            return Optional.of("SMTP not enabled");
        }

        if (StringUtils.hasText(brevoApiKey)) {
            return sendViaBrevoApi(toEmail, toName, subject, body, boardId);
        }

        return sendViaSmtp(toEmail, subject, body, boardId, link);
    }

    private Optional<String> sendViaBrevoApi(String toEmail, String toName, String subject,
                                             String body, String boardId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sender", Map.of("email", mailFrom, "name", "ThinkBoard"));
            payload.put("to", List.of(Map.of("email", toEmail, "name", toName != null ? toName : toEmail)));
            payload.put("subject", subject);
            payload.put("textContent", body);

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(BREVO_SMTP_EMAIL)
                    .timeout(Duration.ofSeconds(30))
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("api-key", brevoApiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Board invite email sent via Brevo API to {} for board {}", toEmail, boardId);
                return Optional.empty();
            }

            String detail = "Brevo API HTTP " + response.statusCode();
            if (StringUtils.hasText(response.body())) {
                detail += ": " + truncate(response.body(), 180);
            }
            log.error("Failed to send invite email to {} via Brevo API: {}", toEmail, detail);
            return Optional.of(detail);
        } catch (Exception e) {
            log.error("Failed to send invite email to {} via Brevo API: {}", toEmail, e.getMessage());
            return Optional.of(e.getMessage() != null ? e.getMessage() : "Brevo API send failed");
        }
    }

    private Optional<String> sendViaSmtp(String toEmail, String subject, String body,
                                         String boardId, String link) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.info("BOARD INVITE (email not sent — set BREVO_API_KEY or SMTP): to={} link={}", toEmail, link);
            log.info("BOARD INVITE email body:\n{}", body);
            return Optional.of("mail not configured (set BREVO_API_KEY or SMTP)");
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Board invite email sent via SMTP to {} for board {}", toEmail, boardId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to send invite email to {} via SMTP: {}", toEmail, e.getMessage());
            log.info("BOARD INVITE fallback link for {}: {}", toEmail, link);
            return Optional.of(e.getMessage() != null ? e.getMessage() : "SMTP send failed");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
