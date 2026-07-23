package com.settlement.pacing.api.error;

public class CampaignNotReservableException
        extends RuntimeException {

    private CampaignNotReservableException(String message) {
        super(message);
    }

    public static CampaignNotReservableException inactive(
            String campaignId
    ) {
        return new CampaignNotReservableException(
                "ACTIVE 상태가 아닌 캠페인에는 예산을 예약할 수 없습니다: "
                        + campaignId
        );
    }

    public static CampaignNotReservableException outsidePeriod(
            String campaignId
    ) {
        return new CampaignNotReservableException(
                "집행 기간 밖의 캠페인에는 예산을 예약할 수 없습니다: "
                        + campaignId
        );
    }
}
