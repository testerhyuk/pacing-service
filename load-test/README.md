# 페이싱 판단 부하 테스트

테스트 전에 대상 캠페인과 해당 날짜의 예산 상태가 준비돼 있어야 한다.

```powershell
$env:SECRET="<ad-server HMAC key>"
$env:CAMPAIGN_ID="<active campaign id>"
k6 run .\load-test\pacing-decision.js
```

기본 부하는 2분간 초당 1,000건이며 `RATE`, `DURATION`,
`PRE_ALLOCATED_VUS`, `MAX_VUS`, `BASE_URL` 환경변수로 변경한다.
