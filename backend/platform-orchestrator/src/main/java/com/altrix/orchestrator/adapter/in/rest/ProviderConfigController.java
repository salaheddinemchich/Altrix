package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.ProviderConfigResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.SaveProviderConfigRequest;
import com.altrix.orchestrator.domain.port.in.GetProviderConfigsUseCase;
import com.altrix.orchestrator.domain.port.in.SaveProviderConfigCommand;
import com.altrix.orchestrator.domain.port.in.UpdateProviderConfigUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST adapter for managing AI provider configuration overrides.
 *
 * <p>Exposes the minimum surface needed:
 * <ul>
 *   <li>GET  /api/ai/providers       — list all providers with their effective config</li>
 *   <li>PUT  /api/ai/providers/{id}  — create or update a provider override</li>
 * </ul>
 *
 * <p>The API key is write-only: it is accepted on PUT but never returned on GET.
 * Pass an empty string for any field to clear its override and revert to the
 * system default from application.yml.
 */
@RestController
@RequestMapping("/api/ai/providers")
@RequiredArgsConstructor
public class ProviderConfigController {

    private final GetProviderConfigsUseCase getUseCase;
    private final UpdateProviderConfigUseCase updateUseCase;

    @GetMapping
    public ResponseEntity<List<ProviderConfigResponse>> listProviders() {
        List<ProviderConfigResponse> body = getUseCase.getProviderConfigs()
                .stream()
                .map(ProviderConfigResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{providerId}")
    public ResponseEntity<Void> updateProvider(
            @PathVariable String providerId,
            @Valid @RequestBody SaveProviderConfigRequest request) {

        updateUseCase.updateProviderConfig(new SaveProviderConfigCommand(
                providerId,
                request.enabled(),
                request.apiKey(),
                request.baseUrl(),
                request.modelAnalysis(),
                request.modelMigration()
        ));
        return ResponseEntity.noContent().build();
    }
}
