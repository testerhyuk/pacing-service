package com.settlement.pacing.api.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("요청값이 올바르지 않습니다");

        return response(
                HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST,
                message,
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST,
                "요청 본문을 읽을 수 없습니다",
                request
        );
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            InvalidRequestException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCampaignNotFound(
            CampaignNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                ErrorCode.CAMPAIGN_NOT_FOUND,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(CampaignNotReservableException.class)
    public ResponseEntity<ErrorResponse> handleCampaignNotReservable(
            CampaignNotReservableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                ErrorCode.CAMPAIGN_NOT_RESERVABLE,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(InsufficientBudgetException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBudget(
            InsufficientBudgetException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                ErrorCode.INSUFFICIENT_BUDGET,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<ErrorResponse> handleReservationConflict(
            ReservationConflictException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                ErrorCode.RESERVATION_CONFLICT,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(BudgetStateUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleBudgetStateUnavailable(
            BudgetStateUnavailableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.BUDGET_STATE_UNAVAILABLE,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(PacingStateUpdateException.class)
    public ResponseEntity<ErrorResponse> handlePacingStateUpdateFailure(
            PacingStateUpdateException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.PACING_STATE_UPDATE_FAILED,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleStorageUnavailable(
            DataAccessException exception,
            HttpServletRequest request
    ) {
        log.error("저장소 접근 중 오류가 발생했습니다", exception);

        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.STORAGE_UNAVAILABLE,
                "저장소에 일시적으로 접근할 수 없습니다",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("예상하지 못한 오류가 발생했습니다", exception);

        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR,
                "서버 내부 오류가 발생했습니다",
                request
        );
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            HttpServletRequest request
    ) {
        ErrorResponse body = ErrorResponse.of(
                errorCode,
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(body);
    }
}
