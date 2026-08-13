package com.settlement.pacing.api.decision.web;

import com.settlement.pacing.api.decision.application.PacingDecisionCommand;
import com.settlement.pacing.api.decision.application.PacingDecisionResult;
import com.settlement.pacing.api.decision.application.PacingDecisionService;
import com.settlement.pacing.api.error.BudgetStateUnavailableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.GlobalExceptionHandler;
import com.settlement.pacing.api.error.InvalidRequestException;
import com.settlement.pacing.api.monitoring.StorageAvailabilityMonitor;
import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PacingDecisionControllerTest {
    private static final String ENDPOINT =
            "/internal/v1/pacing/decisions/decide";
    private static final Instant DECIDED_AT =
            Instant.parse("2026-07-23T03:00:01Z");

    private PacingDecisionService pacingDecisionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pacingDecisionService = mock(PacingDecisionService.class);

        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        PacingDecisionController controller =
                new PacingDecisionController(pacingDecisionService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        mock(StorageAvailabilityMonitor.class)
                ))
                .setValidator(validator)
                .build();
    }

    @Test
    void 정상_PASS_응답은_200이다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenReturn(result(
                DecisionType.PASS,
                DecisionReason.PASS,
                0.60
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.reason").value("PASS"));
    }

    @Test
    void 정상_BLOCK_응답은_200이다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenReturn(result(
                DecisionType.BLOCK,
                DecisionReason.PACING_REJECTED,
                0.30
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.reason")
                        .value("PACING_REJECTED"));
    }

    @Test
    void requestId_누락은_400이다() throws Exception {
        String request = """
                {
                  "campaignId": "campaign-1",
                  "requestedAt": "2026-07-23T03:00:00Z"
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void campaignId_누락은_400이다() throws Exception {
        String request = """
                {
                  "requestId": "request-1",
                  "requestedAt": "2026-07-23T03:00:00Z"
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void requestedAt_누락은_400이다() throws Exception {
        String request = """
                {
                  "requestId": "request-1",
                  "campaignId": "campaign-1"
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 캠페인_없음은_404이다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenThrow(new CampaignNotFoundException("campaign-1"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("CAMPAIGN_NOT_FOUND"));
    }

    @Test
    void 예산_상태_없음은_503이다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenThrow(new BudgetStateUnavailableException());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("BUDGET_STATE_UNAVAILABLE"));
    }

    @Test
    void 명시적인_요청값_오류는_400이다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenThrow(new InvalidRequestException(
                "요청값이 올바르지 않습니다"
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    void 내부_IllegalArgumentException은_500이다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenThrow(new IllegalArgumentException(
                "내부 도메인 값이 올바르지 않습니다"
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code")
                        .value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void 응답_JSON에_모든_필드가_포함된다() throws Exception {
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenReturn(result(
                DecisionType.PASS,
                DecisionReason.PASS,
                0.60
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId")
                        .value("request-1"))
                .andExpect(jsonPath("$.campaignId")
                        .value("campaign-1"))
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.reason").value("PASS"))
                .andExpect(jsonPath("$.pacingRate").value(0.60))
                .andExpect(jsonPath("$.decidedAt")
                        .value(DECIDED_AT.toString()));
    }

    private PacingDecisionResult result(
            DecisionType decision,
            DecisionReason reason,
            double pacingRate
    ) {
        return new PacingDecisionResult(
                "request-1",
                "campaign-1",
                decision,
                reason,
                pacingRate,
                DECIDED_AT
        );
    }

    private String validRequest() {
        return """
                {
                  "requestId": "request-1",
                  "campaignId": "campaign-1",
                  "requestedAt": "2026-07-23T03:00:00Z"
                }
                """;
    }
}
