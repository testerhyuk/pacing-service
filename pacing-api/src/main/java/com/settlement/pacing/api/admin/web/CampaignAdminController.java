package com.settlement.pacing.api.admin.web;

import com.settlement.pacing.api.admin.application.CampaignAdminService;
import com.settlement.pacing.api.security.HmacAuthenticationFilter;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/v1/campaigns")
public class CampaignAdminController {
    private final CampaignAdminService campaignAdminService;

    public CampaignAdminController(
            CampaignAdminService campaignAdminService
    ) {
        this.campaignAdminService = campaignAdminService;
    }

    @GetMapping("/{campaignId}")
    public CampaignAdminResponse get(
            @PathVariable String campaignId
    ) {
        return CampaignAdminResponse.from(
                campaignAdminService.find(campaignId)
        );
    }

    @PutMapping("/{campaignId}")
    public CampaignAdminResponse upsert(
            @PathVariable String campaignId,
            @Valid @RequestBody CampaignUpsertRequest request,
            @RequestHeader(
                    value = HmacAuthenticationFilter.NONCE_HEADER,
                    required = false
            ) String requestId,
            Authentication authentication
    ) {
        return CampaignAdminResponse.from(
                campaignAdminService.upsert(
                        request.toCommand(campaignId),
                        authentication.getName(),
                        requestId
                )
        );
    }
}
