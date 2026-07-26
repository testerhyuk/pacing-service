package com.settlement.pacing.api.decision.application;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.decision.support.SampleRateGenerator;
import com.settlement.pacing.api.error.BudgetStateUnavailableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.PacingStateUpdateException;
import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.api.gateway.PacingObservationGateway;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;
import com.settlement.pacing.core.pacing.PacingDecision;
import com.settlement.pacing.core.pacing.PacingEngine;
import com.settlement.pacing.core.pacing.PacingRequest;
import com.settlement.pacing.core.pacing.PacingResult;
import com.settlement.pacing.core.pacing.PacingState;
import com.settlement.pacing.core.pacing.PacingObservation;
import com.settlement.pacing.core.pacing.Rate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PacingDecisionServiceTest {
    private static final String REQUEST_ID = "request-1";
    private static final String CAMPAIGN_ID = "campaign-1";
    private static final Instant REQUESTED_AT =
            Instant.parse("2026-07-23T03:00:00Z");
    private static final Instant DECIDED_AT =
            Instant.parse("2026-07-23T03:00:01Z");
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 7, 23);
    private static final Rate SAMPLE_RATE = new Rate(0.25);
    private static final Rate INITIAL_RATE = new Rate(0.40);

    private CampaignQueryGateway campaignQueryGateway;
    private BudgetStateQueryGateway budgetStateQueryGateway;
    private PacingStateGateway pacingStateGateway;
    private PacingObservationGateway pacingObservationGateway;
    private PacingEngine pacingEngine;
    private SampleRateGenerator sampleRateGenerator;
    private PacingApiMetrics pacingApiMetrics;

    private PacingDecisionService service;
    private Campaign campaign;
    private BudgetState budgetState;
    private PacingState initialPacingState;
    private PacingStateSnapshot initialSnapshot;
    private PacingDecisionCommand command;

    @BeforeEach
    void setUp() {
        campaignQueryGateway = mock(CampaignQueryGateway.class);
        budgetStateQueryGateway = mock(BudgetStateQueryGateway.class);
        pacingStateGateway = mock(PacingStateGateway.class);
        pacingObservationGateway =
                mock(PacingObservationGateway.class);
        pacingEngine = mock(PacingEngine.class);
        sampleRateGenerator = mock(SampleRateGenerator.class);
        pacingApiMetrics = mock(PacingApiMetrics.class);

        PacingProperties properties = properties(3);
        Clock clock = Clock.fixed(DECIDED_AT, ZoneOffset.UTC);

        service = new PacingDecisionService(
                campaignQueryGateway,
                budgetStateQueryGateway,
                pacingStateGateway,
                pacingObservationGateway,
                pacingEngine,
                sampleRateGenerator,
                properties,
                clock,
                pacingApiMetrics
        );

        campaign = new Campaign(
                CAMPAIGN_ID,
                CampaignStatus.ACTIVE,
                REQUESTED_AT.minus(Duration.ofDays(1)),
                REQUESTED_AT.plus(Duration.ofDays(1)),
                PacingStrategy.EVEN
        );

        budgetState = budgetState(100_000L);
        initialPacingState = new PacingState(INITIAL_RATE, REQUESTED_AT);
        initialSnapshot = new PacingStateSnapshot(initialPacingState, 1L);
        command = new PacingDecisionCommand(
                REQUEST_ID,
                CAMPAIGN_ID,
                REQUESTED_AT
        );

        when(campaignQueryGateway.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign));
        when(budgetStateQueryGateway.find(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(Optional.of(budgetState));
        when(pacingStateGateway.getOrInitialize(
                eq(CAMPAIGN_ID),
                any(PacingState.class)
        )).thenReturn(initialSnapshot);
        when(sampleRateGenerator.generate(REQUEST_ID, CAMPAIGN_ID))
                .thenReturn(SAMPLE_RATE);
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                eq(SAMPLE_RATE),
                eq(PacingObservation.empty())
        )).thenReturn(passResult(initialPacingState));
    }

    @Test
    void 캠페인과_예산_상태를_조회하여_페이싱_엔진에_전달한다() {
        service.decide(command);

        verify(campaignQueryGateway).findById(CAMPAIGN_ID);
        verify(budgetStateQueryGateway).find(CAMPAIGN_ID, BUDGET_DATE);
        verify(pacingEngine).decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                eq(SAMPLE_RATE),
                eq(PacingObservation.empty())
        );
    }

    @Test
    void PASS_판단을_캠페인_관측_통계에_기록한다() {
        service.decide(command);

        verify(pacingObservationGateway).recordDecision(
                REQUEST_ID,
                CAMPAIGN_ID,
                DecisionType.PASS,
                DECIDED_AT
        );
    }

    @Test
    void 비율_갱신_시점에는_최근_관측_통계를_엔진에_전달한다() {
        PacingState dueState = new PacingState(
                INITIAL_RATE,
                DECIDED_AT.minusSeconds(10)
        );
        PacingStateSnapshot dueSnapshot =
                new PacingStateSnapshot(dueState, 1L);
        PacingObservation observation =
                new PacingObservation(
                        6,
                        600L,
                        120L,
                        24L,
                        new Money(24_000L)
                );

        when(pacingStateGateway.getOrInitialize(
                eq(CAMPAIGN_ID),
                any(PacingState.class)
        )).thenReturn(dueSnapshot);
        when(pacingObservationGateway.recent(
                CAMPAIGN_ID,
                DECIDED_AT
        )).thenReturn(observation);
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(dueState),
                eq(SAMPLE_RATE),
                eq(observation)
        )).thenReturn(passResult(dueState));

        service.decide(command);

        verify(pacingObservationGateway).recent(
                CAMPAIGN_ID,
                DECIDED_AT
        );
        verify(pacingEngine).decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(dueState),
                eq(SAMPLE_RATE),
                eq(observation)
        );
    }

    @Test
    void 예산_소진_BLOCK은_트래픽_수용량_관측에서_제외한다() {
        PacingResult exhausted = new PacingResult(
                PacingDecision.block(
                        DecisionReason.BUDGET_EXHAUSTED,
                        INITIAL_RATE
                ),
                initialPacingState
        );
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(exhausted);

        service.decide(command);

        verify(pacingObservationGateway, never())
                .recordDecision(
                        any(String.class),
                        any(String.class),
                        any(DecisionType.class),
                        any(Instant.class)
                );
    }

    @Test
    void 캠페인이_없으면_CampaignNotFoundException을_발생시킨다() {
        when(campaignQueryGateway.findById(CAMPAIGN_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(command))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void 예산_상태가_없으면_BudgetStateUnavailableException을_발생시킨다() {
        when(budgetStateQueryGateway.find(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(command))
                .isInstanceOf(BudgetStateUnavailableException.class);
    }

    @Test
    void 페이싱_상태가_없으면_설정된_초기_비율로_생성한다() {
        ArgumentCaptor<PacingState> stateCaptor =
                ArgumentCaptor.forClass(PacingState.class);

        service.decide(command);

        verify(pacingStateGateway).getOrInitialize(
                eq(CAMPAIGN_ID),
                stateCaptor.capture()
        );
        assertThat(stateCaptor.getValue().pacingRate())
                .isEqualTo(INITIAL_RATE);
        assertThat(stateCaptor.getValue().updatedAt())
                .isEqualTo(DECIDED_AT);
    }

    @Test
    void 서버_시각의_허용_범위를_벗어난_requestedAt을_거절한다() {
        PacingDecisionCommand invalid = new PacingDecisionCommand(
                REQUEST_ID,
                CAMPAIGN_ID,
                DECIDED_AT.plusSeconds(61)
        );

        assertThatThrownBy(() -> service.decide(invalid))
                .isInstanceOf(
                        com.settlement.pacing.api.error
                                .InvalidRequestException.class
                );

        verify(campaignQueryGateway, never())
                .findById(any(String.class));
    }

    @Test
    void 동일한_requestId와_campaignId는_동일한_샘플을_사용한다() {
        service.decide(command);
        service.decide(command);

        ArgumentCaptor<Rate> sampleCaptor =
                ArgumentCaptor.forClass(Rate.class);
        verify(pacingEngine, times(2)).decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                sampleCaptor.capture(),
                any(PacingObservation.class)
        );

        List<Rate> samples = sampleCaptor.getAllValues();
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0)).isEqualTo(samples.get(1));
    }

    @Test
    void 페이싱_상태가_변경되지_않으면_저장하지_않는다() {
        service.decide(command);

        verify(pacingStateGateway, never()).compareAndSet(
                any(String.class),
                anyLong(),
                any(PacingState.class)
        );
    }

    @Test
    void 페이싱_상태가_변경되면_compareAndSet으로_저장한다() {
        PacingState changedState = new PacingState(
                new Rate(0.60),
                REQUESTED_AT.plusSeconds(1)
        );
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(passResult(changedState));
        when(pacingStateGateway.compareAndSet(
                CAMPAIGN_ID,
                1L,
                changedState
        )).thenReturn(true);

        service.decide(command);

        verify(pacingStateGateway).compareAndSet(
                CAMPAIGN_ID,
                1L,
                changedState
        );
    }

    @Test
    void compareAndSet_충돌_시_최신_상태와_예산으로_다시_판단한다() {
        BudgetState latestBudgetState = budgetState(200_000L);
        PacingState latestState = new PacingState(
                new Rate(0.45),
                REQUESTED_AT.plusSeconds(1)
        );
        PacingStateSnapshot latestSnapshot =
                new PacingStateSnapshot(latestState, 2L);
        PacingState firstChangedState = new PacingState(
                new Rate(0.60),
                REQUESTED_AT.plusSeconds(2)
        );
        PacingState secondChangedState = new PacingState(
                new Rate(0.70),
                REQUESTED_AT.plusSeconds(3)
        );

        when(budgetStateQueryGateway.find(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(
                        Optional.of(budgetState),
                        Optional.of(latestBudgetState)
                );
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(passResult(firstChangedState));
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(latestBudgetState),
                eq(latestState),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(passResult(secondChangedState));
        when(pacingStateGateway.compareAndSet(
                CAMPAIGN_ID,
                1L,
                firstChangedState
        )).thenReturn(false);
        when(pacingStateGateway.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(Optional.of(latestSnapshot));
        when(pacingStateGateway.compareAndSet(
                CAMPAIGN_ID,
                2L,
                secondChangedState
        )).thenReturn(true);

        service.decide(command);

        verify(pacingEngine).decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(latestBudgetState),
                eq(latestState),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        );
    }

    @Test
    void 재시도에서도_최초와_동일한_sampleRate를_사용한다() {
        PacingState latestState = new PacingState(
                new Rate(0.45),
                REQUESTED_AT.plusSeconds(1)
        );
        PacingState changedState = new PacingState(
                new Rate(0.60),
                REQUESTED_AT.plusSeconds(2)
        );

        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                any(PacingState.class),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(passResult(changedState));
        when(pacingStateGateway.compareAndSet(
                eq(CAMPAIGN_ID),
                anyLong(),
                eq(changedState)
        )).thenReturn(false, true);
        when(pacingStateGateway.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(Optional.of(
                        new PacingStateSnapshot(latestState, 2L)
                ));

        service.decide(command);

        ArgumentCaptor<Rate> sampleCaptor =
                ArgumentCaptor.forClass(Rate.class);
        verify(pacingEngine, times(2)).decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                any(PacingState.class),
                sampleCaptor.capture(),
                any(PacingObservation.class)
        );
        assertThat(sampleCaptor.getAllValues())
                .containsExactly(SAMPLE_RATE, SAMPLE_RATE);
    }

    @Test
    void 최대_재시도_횟수를_초과하면_PacingStateUpdateException을_발생시킨다() {
        PacingState latestState = new PacingState(
                new Rate(0.45),
                REQUESTED_AT.plusSeconds(1)
        );
        PacingState changedState = new PacingState(
                new Rate(0.60),
                REQUESTED_AT.plusSeconds(2)
        );

        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                any(PacingState.class),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(passResult(changedState));
        when(pacingStateGateway.compareAndSet(
                eq(CAMPAIGN_ID),
                anyLong(),
                eq(changedState)
        )).thenReturn(false);
        when(pacingStateGateway.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(Optional.of(
                        new PacingStateSnapshot(latestState, 2L)
                ));

        assertThatThrownBy(() -> service.decide(command))
                .isInstanceOf(PacingStateUpdateException.class);

        verify(pacingStateGateway, times(4)).compareAndSet(
                eq(CAMPAIGN_ID),
                anyLong(),
                eq(changedState)
        );
    }

    @Test
    void PacingResult의_결정_사유와_비율을_Result에_반영한다() {
        PacingDecisionResult result = service.decide(command);

        assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        assertThat(result.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(result.decision()).isEqualTo(DecisionType.PASS);
        assertThat(result.reason()).isEqualTo(DecisionReason.PASS);
        assertThat(result.pacingRate()).isEqualTo(INITIAL_RATE.value());
        assertThat(result.decidedAt()).isEqualTo(DECIDED_AT);
    }

    @Test
    void PASS_판단에서는_BudgetState를_변경하지_않는다() {
        BudgetState before = budgetState;

        service.decide(command);

        assertThat(budgetState).isEqualTo(before);
        verify(pacingStateGateway, never()).compareAndSet(
                any(String.class),
                anyLong(),
                any(PacingState.class)
        );
    }

    @Test
    void BLOCK_판단에서는_BudgetState를_변경하지_않는다() {
        PacingResult blockResult = new PacingResult(
                PacingDecision.block(INITIAL_RATE),
                initialPacingState
        );
        when(pacingEngine.decide(
                any(PacingRequest.class),
                eq(campaign),
                eq(budgetState),
                eq(initialPacingState),
                eq(SAMPLE_RATE),
                any(PacingObservation.class)
        )).thenReturn(blockResult);
        BudgetState before = budgetState;

        PacingDecisionResult result = service.decide(command);

        assertThat(result.decision()).isEqualTo(DecisionType.BLOCK);
        assertThat(budgetState).isEqualTo(before);
        verify(pacingStateGateway, never()).compareAndSet(
                any(String.class),
                anyLong(),
                any(PacingState.class)
        );
    }

    private PacingProperties properties(int maxRetries) {
        return new PacingProperties(
                ZoneOffset.UTC,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                new PacingProperties.Observation(
                        Duration.ofMinutes(1),
                        20L,
                        0.5,
                        0.2,
                        0.1
                ),
                Duration.ofMinutes(5),
                maxRetries,
                new PacingProperties.InitialRate(0.40, 0.30, 1.0),
                new PacingProperties.Peak(
                        LocalTime.of(18, 0),
                        LocalTime.of(22, 0),
                        ZoneId.of("Asia/Seoul"),
                        1.0,
                        2.0
                )
        );
    }

    private BudgetState budgetState(long totalSpentAmount) {
        return new BudgetState(
                CAMPAIGN_ID,
                BUDGET_DATE,
                new Money(1_000_000L),
                new Money(totalSpentAmount),
                Money.zero(),
                new Money(500_000L),
                new Money(totalSpentAmount),
                Money.zero()
        );
    }

    private PacingResult passResult(PacingState state) {
        return new PacingResult(
                PacingDecision.pass(state.pacingRate()),
                state
        );
    }
}
