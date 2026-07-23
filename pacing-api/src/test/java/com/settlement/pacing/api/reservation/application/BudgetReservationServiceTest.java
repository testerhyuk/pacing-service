package com.settlement.pacing.api.reservation.application;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.error.BudgetStateUnavailableException;
import com.settlement.pacing.api.error.CampaignNotReservableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.InsufficientBudgetException;
import com.settlement.pacing.api.error.ReservationConflictException;
import com.settlement.pacing.api.gateway.BudgetReservationGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.ReservationExecutionResult;
import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetReservationServiceTest {
    private static final String RESERVATION_ID = "reservation-1";
    private static final String CAMPAIGN_ID = "campaign-1";
    private static final long AMOUNT = 120_000L;
    private static final Instant NOW =
            Instant.parse("2026-07-22T15:30:00Z");
    private static final Duration RESERVATION_TTL =
            Duration.ofMinutes(5);
    private static final ZoneId BUSINESS_ZONE_ID =
            ZoneId.of("Asia/Seoul");
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 7, 23);

    private CampaignQueryGateway campaignQueryGateway;
    private BudgetReservationGateway budgetReservationGateway;
    private PacingApiMetrics pacingApiMetrics;
    private BudgetReservationService service;
    private BudgetReservationCommand command;
    private Campaign activeCampaign;

    @BeforeEach
    void setUp() {
        campaignQueryGateway = mock(CampaignQueryGateway.class);
        budgetReservationGateway = mock(BudgetReservationGateway.class);
        pacingApiMetrics = mock(PacingApiMetrics.class);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PacingProperties properties = properties();

        service = new BudgetReservationService(
                campaignQueryGateway,
                budgetReservationGateway,
                properties,
                clock,
                pacingApiMetrics
        );

        command = new BudgetReservationCommand(
                RESERVATION_ID,
                CAMPAIGN_ID,
                AMOUNT
        );
        activeCampaign = campaign(
                CampaignStatus.ACTIVE,
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(1))
        );

        when(budgetReservationGateway.findById(RESERVATION_ID))
                .thenReturn(Optional.empty());
        when(campaignQueryGateway.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(activeCampaign));
        when(budgetReservationGateway.reserve(
                any(BudgetReservation.class)
        )).thenAnswer(invocation -> new ReservationExecutionResult(
                ReservationExecutionStatus.CREATED,
                invocation.getArgument(0)
        ));
    }

    @Test
    void 신규_예약은_서버_현재_시각을_reservedAt으로_사용한다() {
        service.reserve(command);

        BudgetReservation reservation = capturedReservation();

        assertThat(reservation.reservedAt()).isEqualTo(NOW);
    }

    @Test
    void budgetDate는_비즈니스_타임존으로_계산한다() {
        service.reserve(command);

        BudgetReservation reservation = capturedReservation();

        assertThat(reservation.budgetDate()).isEqualTo(BUDGET_DATE);
    }

    @Test
    void expiresAt은_reservedAt에_reservationTtl을_더해_계산한다() {
        service.reserve(command);

        BudgetReservation reservation = capturedReservation();

        assertThat(reservation.expiresAt())
                .isEqualTo(NOW.plus(RESERVATION_TTL));
    }

    @Test
    void 신규_예약은_RESERVED_상태로_생성한다() {
        service.reserve(command);

        BudgetReservation reservation = capturedReservation();

        assertThat(reservation.status())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void CREATED_결과를_created_true로_반환한다() {
        BudgetReservationResult result = service.reserve(command);

        assertThat(result.created()).isTrue();
        assertThat(result.reservation().reservationId())
                .isEqualTo(RESERVATION_ID);
    }

    @Test
    void 동일한_기존_예약은_created_false로_반환한다() {
        BudgetReservation existingReservation =
                existingReservation(CAMPAIGN_ID, AMOUNT);
        when(budgetReservationGateway.findById(RESERVATION_ID))
                .thenReturn(Optional.of(existingReservation));

        BudgetReservationResult result = service.reserve(command);

        assertThat(result.created()).isFalse();
        assertThat(result.reservation())
                .isEqualTo(existingReservation);
        verify(budgetReservationGateway, never())
                .reserve(any(BudgetReservation.class));
    }

    @Test
    void 기존_예약_재시도에서는_캠페인_상태를_다시_검사하지_않는다() {
        BudgetReservation existingReservation =
                existingReservation(CAMPAIGN_ID, AMOUNT);
        when(budgetReservationGateway.findById(RESERVATION_ID))
                .thenReturn(Optional.of(existingReservation));

        service.reserve(command);

        verify(campaignQueryGateway, never()).findById(CAMPAIGN_ID);
    }

    @Test
    void 같은_예약_ID에_다른_campaignId가_들어오면_충돌한다() {
        BudgetReservation existingReservation =
                existingReservation(CAMPAIGN_ID, AMOUNT);
        BudgetReservationCommand differentCampaignCommand =
                new BudgetReservationCommand(
                        RESERVATION_ID,
                        "campaign-2",
                        AMOUNT
                );
        when(budgetReservationGateway.findById(RESERVATION_ID))
                .thenReturn(Optional.of(existingReservation));

        assertThatThrownBy(
                () -> service.reserve(differentCampaignCommand)
        ).isInstanceOf(ReservationConflictException.class);

        verify(campaignQueryGateway, never()).findById(any(String.class));
        verify(budgetReservationGateway, never())
                .reserve(any(BudgetReservation.class));
    }

    @Test
    void 같은_예약_ID에_다른_amount가_들어오면_충돌한다() {
        BudgetReservation existingReservation =
                existingReservation(CAMPAIGN_ID, AMOUNT);
        BudgetReservationCommand differentAmountCommand =
                new BudgetReservationCommand(
                        RESERVATION_ID,
                        CAMPAIGN_ID,
                        AMOUNT + 1L
                );
        when(budgetReservationGateway.findById(RESERVATION_ID))
                .thenReturn(Optional.of(existingReservation));

        assertThatThrownBy(
                () -> service.reserve(differentAmountCommand)
        ).isInstanceOf(ReservationConflictException.class);

        verify(campaignQueryGateway, never()).findById(any(String.class));
        verify(budgetReservationGateway, never())
                .reserve(any(BudgetReservation.class));
    }

    @Test
    void 캠페인이_없으면_CampaignNotFoundException을_발생시킨다() {
        when(campaignQueryGateway.findById(CAMPAIGN_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(command))
                .isInstanceOf(CampaignNotFoundException.class);

        verify(budgetReservationGateway, never())
                .reserve(any(BudgetReservation.class));
    }

    @Test
    void 캠페인이_ACTIVE가_아니면_CampaignNotReservableException을_발생시킨다() {
        Campaign pausedCampaign = campaign(
                CampaignStatus.PAUSED,
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(1))
        );
        when(campaignQueryGateway.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(pausedCampaign));

        assertThatThrownBy(() -> service.reserve(command))
                .isInstanceOf(CampaignNotReservableException.class)
                .hasMessageContaining("ACTIVE 상태");

        verify(budgetReservationGateway, never())
                .reserve(any(BudgetReservation.class));
    }

    @Test
    void 캠페인_기간_밖이면_CampaignNotReservableException을_발생시킨다() {
        Campaign futureCampaign = campaign(
                CampaignStatus.ACTIVE,
                NOW.plus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1))
        );
        when(campaignQueryGateway.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(futureCampaign));

        assertThatThrownBy(() -> service.reserve(command))
                .isInstanceOf(CampaignNotReservableException.class)
                .hasMessageContaining("집행 기간 밖");

        verify(budgetReservationGateway, never())
                .reserve(any(BudgetReservation.class));
    }

    @Test
    void 예산_부족_결과를_InsufficientBudgetException으로_변환한다() {
        when(budgetReservationGateway.reserve(
                any(BudgetReservation.class)
        )).thenReturn(new ReservationExecutionResult(
                ReservationExecutionStatus.INSUFFICIENT_BUDGET,
                null
        ));

        assertThatThrownBy(() -> service.reserve(command))
                .isInstanceOf(InsufficientBudgetException.class);
    }

    @Test
    void 예산_상태_없음_결과를_BudgetStateUnavailableException으로_변환한다() {
        when(budgetReservationGateway.reserve(
                any(BudgetReservation.class)
        )).thenReturn(new ReservationExecutionResult(
                ReservationExecutionStatus.BUDGET_STATE_NOT_FOUND,
                null
        ));

        assertThatThrownBy(() -> service.reserve(command))
                .isInstanceOf(BudgetStateUnavailableException.class);
    }

    @Test
    void Gateway의_ALREADY_EXISTS_경합_결과도_멱등하게_처리한다() {
        BudgetReservation existingReservation =
                existingReservation(CAMPAIGN_ID, AMOUNT);
        when(budgetReservationGateway.reserve(
                any(BudgetReservation.class)
        )).thenReturn(new ReservationExecutionResult(
                ReservationExecutionStatus.ALREADY_EXISTS,
                existingReservation
        ));

        BudgetReservationResult result = service.reserve(command);

        assertThat(result.created()).isFalse();
        assertThat(result.reservation())
                .isEqualTo(existingReservation);
    }

    private BudgetReservation capturedReservation() {
        ArgumentCaptor<BudgetReservation> captor =
                ArgumentCaptor.forClass(BudgetReservation.class);
        verify(budgetReservationGateway).reserve(captor.capture());
        return captor.getValue();
    }

    private Campaign campaign(
            CampaignStatus status,
            Instant startAt,
            Instant endAt
    ) {
        return new Campaign(
                CAMPAIGN_ID,
                status,
                startAt,
                endAt,
                PacingStrategy.EVEN
        );
    }

    private BudgetReservation existingReservation(
            String campaignId,
            long amount
    ) {
        return new BudgetReservation(
                RESERVATION_ID,
                campaignId,
                BUDGET_DATE,
                new Money(amount),
                NOW.minus(Duration.ofMinutes(1)),
                NOW.plus(Duration.ofMinutes(4))
        );
    }

    private PacingProperties properties() {
        return new PacingProperties(
                BUSINESS_ZONE_ID,
                Duration.ofSeconds(10),
                0.1,
                RESERVATION_TTL,
                3,
                new PacingProperties.InitialRate(
                        0.40,
                        0.30,
                        1.0
                ),
                new PacingProperties.Peak(
                        LocalTime.of(18, 0),
                        LocalTime.of(22, 0),
                        BUSINESS_ZONE_ID,
                        1.0,
                        2.0
                )
        );
    }
}
