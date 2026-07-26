package com.settlement.pacing.api.admin.web;

import com.settlement.pacing.api.admin.application.DynamicPeakPolicyService;
import com.settlement.pacing.api.security.HmacAuthenticationFilter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/v1/peak-policy")
public class PeakPolicyAdminController {
    private final DynamicPeakPolicyService peakPolicyService;

    public PeakPolicyAdminController(
            DynamicPeakPolicyService peakPolicyService
    ) {
        this.peakPolicyService = peakPolicyService;
    }

    @GetMapping
    public PeakPolicyResponse get() {
        return PeakPolicyResponse.from(
                peakPolicyService.current()
        );
    }

    @PutMapping
    public ResponseEntity<PeakPolicyResponse> update(
            @Valid @RequestBody PeakPolicyRequest request,
            @RequestHeader(
                    value = HmacAuthenticationFilter.NONCE_HEADER,
                    required = false
            ) String requestId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(PeakPolicyResponse.from(
                peakPolicyService.update(
                        request.toCommand(),
                        authentication.getName(),
                        requestId
                )
        ));
    }
}
