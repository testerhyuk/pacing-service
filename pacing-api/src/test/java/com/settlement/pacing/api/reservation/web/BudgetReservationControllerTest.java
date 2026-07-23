package com.settlement.pacing.api.reservation.web;

import com.settlement.pacing.api.error.CampaignNotReservableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.GlobalExceptionHandler;
import com.settlement.pacing.api.error.InsufficientBudgetException;
import com.settlement.pacing.api.error.ReservationConflictException;
import com.settlement.pacing.api.reservation.application.BudgetReservationCommand;
import com.settlement.pacing.api.reservation.application.BudgetReservationResult;
import com.settlement.pacing.api.reservation.application.BudgetReservationService;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BudgetReservationControllerTest {
    private static final String ENDPOINT =
            "/internal/v1/budget-reservations";
    private static final String RESERVATION_ID = "reservation-1";
    private static final String CAMPAIGN_ID = "campaign-1";
    private static final long AMOUNT = 120_000L;
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 7, 23);
    private static final Instant RESERVED_AT =
            Instant.parse("2026-07-22T15:30:00Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-07-22T15:35:00Z");

    private BudgetReservationService budgetReservationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        budgetReservationService = mock(BudgetReservationService.class);

        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        BudgetReservationController controller =
                new BudgetReservationController(
                        budgetReservationService
                );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void 신규_예약_성공은_201이다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenReturn(result(true));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void 기존_예약_반환은_200이다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenReturn(result(false));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void reservationId_누락은_400이다() throws Exception {
        String request = """
                {
                  "campaignId": "campaign-1",
                  "amount": 120000
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    void campaignId_누락은_400이다() throws Exception {
        String request = """
                {
                  "reservationId": "reservation-1",
                  "amount": 120000
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    void amount가_0이면_400이다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithAmount(0L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    void amount가_음수이면_400이다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithAmount(-1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    void 예산_부족은_409다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenThrow(new InsufficientBudgetException());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("INSUFFICIENT_BUDGET"));
    }

    @Test
    void 예약_ID_충돌은_409다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenThrow(new ReservationConflictException());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("RESERVATION_CONFLICT"));
    }

    @Test
    void 캠페인_없음은_404다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenThrow(new CampaignNotFoundException(CAMPAIGN_ID));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("CAMPAIGN_NOT_FOUND"));
    }

    @Test
    void 예약할_수_없는_캠페인은_409다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenThrow(
                CampaignNotReservableException.inactive(
                        CAMPAIGN_ID
                )
        );

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("CAMPAIGN_NOT_RESERVABLE"));
    }

    @Test
    void 저장소_장애는_503이다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenThrow(new DataAccessResourceFailureException(
                "Redis 연결 실패"
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void 응답_JSON에_모든_필드가_포함된다() throws Exception {
        when(budgetReservationService.reserve(
                any(BudgetReservationCommand.class)
        )).thenReturn(result(true));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId")
                        .value(RESERVATION_ID))
                .andExpect(jsonPath("$.campaignId")
                        .value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.budgetDate")
                        .value(BUDGET_DATE.toString()))
                .andExpect(jsonPath("$.amount").value(AMOUNT))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservedAt")
                        .value(RESERVED_AT.toString()))
                .andExpect(jsonPath("$.expiresAt")
                        .value(EXPIRES_AT.toString()))
                .andExpect(jsonPath("$.created").value(true));
    }

    private BudgetReservationResult result(boolean created) {
        BudgetReservation reservation = new BudgetReservation(
                RESERVATION_ID,
                CAMPAIGN_ID,
                BUDGET_DATE,
                new Money(AMOUNT),
                RESERVED_AT,
                EXPIRES_AT
        );

        return created
                ? BudgetReservationResult.created(reservation)
                : BudgetReservationResult.existing(reservation);
    }

    private String validRequest() {
        return requestWithAmount(AMOUNT);
    }

    private String requestWithAmount(long amount) {
        return """
                {
                  "reservationId": "reservation-1",
                  "campaignId": "campaign-1",
                  "amount": %d
                }
                """.formatted(amount);
    }
}
