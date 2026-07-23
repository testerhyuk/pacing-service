package com.settlement.pacing.api.reservation.web;

import com.settlement.pacing.api.reservation.application.BudgetReservationCommand;
import com.settlement.pacing.api.reservation.application.BudgetReservationResult;
import com.settlement.pacing.api.reservation.application.BudgetReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/budget-reservations")
@RequiredArgsConstructor
public class BudgetReservationController {
    private final BudgetReservationService budgetReservationService;

    @PostMapping
    public ResponseEntity<BudgetReservationResponse> reserve(@Valid @RequestBody BudgetReservationRequest request) {
        BudgetReservationCommand command = request.toCommand();

        BudgetReservationResult reserved = budgetReservationService.reserve(command);

        BudgetReservationResponse response = BudgetReservationResponse.from(reserved);

        if (reserved.created()) return ResponseEntity.status(HttpStatus.CREATED).body(response);
        else return ResponseEntity.ok(response);
    }
}
