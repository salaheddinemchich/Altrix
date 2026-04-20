package com.migrator.project.adapter.in.rest;

import com.migrator.common.domain.model.MigrationJobRecord;
import com.migrator.project.domain.port.in.ManageMigrationJobUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class MigrationJobController {

    private final ManageMigrationJobUseCase useCase;

    @PostMapping
    public ResponseEntity<MigrationJobRecord> createJob(@Valid @RequestBody CreateJobRequest request) {
        MigrationJobRecord domainRecord = MigrationJobRecord.builder()
                .name(request.getName())
                .sourceTopic(request.getSourceTopic())
                .targetTopic(request.getTargetTopic())
                .build();
                
        return ResponseEntity.ok(useCase.createJob(domainRecord));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MigrationJobRecord> getJob(@PathVariable UUID id) {
        return ResponseEntity.ok(useCase.getJob(id));
    }

    @GetMapping
    public ResponseEntity<List<MigrationJobRecord>> getAllJobs() {
        return ResponseEntity.ok(useCase.getAllJobs());
    }
}
