package com.settlement.pacing.api.error;

import com.settlement.pacing.api.monitoring.StorageAvailabilityMonitor;
import com.settlement.pacing.api.monitoring.StorageOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    @Test
    void 트랜잭션_생성_실패는_503_저장소_장애로_응답한다() {
        StorageAvailabilityMonitor monitor =
                mock(StorageAvailabilityMonitor.class);
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(monitor);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CannotCreateTransactionException exception =
                new CannotCreateTransactionException(
                        "connection unavailable"
                );
        when(request.getRequestURI()).thenReturn(
                "/internal/v1/pacing/decisions/decide"
        );

        ResponseEntity<ErrorResponse> response =
                handler.handleStorageUnavailable(
                        exception,
                        request
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE.name());
        verify(monitor).recordFailure(
                StorageOperation.DECISION,
                exception
        );
    }
}
