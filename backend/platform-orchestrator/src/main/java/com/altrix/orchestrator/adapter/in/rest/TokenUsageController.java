package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.TokenUsageSummaryResponse;
import com.altrix.orchestrator.domain.port.in.GetTokenUsageUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST adapter for token-usage analytics (#152).
 *
 * <ul>
 *   <li>GET /api/ai/token-usage — aggregated totals per provider+tier</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/token-usage")
@RequiredArgsConstructor
public class TokenUsageController {

    private final GetTokenUsageUseCase getTokenUsageUseCase;

    @GetMapping
    public ResponseEntity<List<TokenUsageSummaryResponse>> getSummary() {
        List<TokenUsageSummaryResponse> body = getTokenUsageUseCase.getSummary()
                .stream()
                .map(TokenUsageSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }
}
