package com.settlement.pacing.api.decision.web;

import com.settlement.pacing.api.decision.application.PacingDecisionCommand;
import com.settlement.pacing.api.decision.application.PacingDecisionResult;
import com.settlement.pacing.api.decision.application.PacingDecisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/pacing/decisions")
@RequiredArgsConstructor
public class PacingDecisionController {
    private final PacingDecisionService pacingDecisionService;

    @PostMapping("/decide")
    public ResponseEntity<PacingDecisionResponse> decide(@Valid @RequestBody PacingDecisionRequest request) {
        PacingDecisionCommand command = request.toCommand();

        PacingDecisionResult result = pacingDecisionService.decide(command);

        PacingDecisionResponse response = PacingDecisionResponse.from(result);

        return ResponseEntity.ok(response);
    }
}
