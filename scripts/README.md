# 로컬 테스트 스크립트

이 폴더의 스크립트는 다음 범위를 검증한다.

- 전체 Gradle 단위·통합 테스트와 실행 JAR 빌드
- Docker Compose 기반 PostgreSQL, Redis, Kafka, API, Worker 기동
- HMAC 인증, 권한 분리, nonce 재사용 차단
- 캠페인과 피크 정책 운영 API
- 페이싱 PASS/BLOCK 판단
- 신규 예약, 중복 예약, 예약 충돌, 예산 부족
- Kafka 과금 확정, 중복 과금 방지, 보정, 취소
- PostgreSQL 처리 이력과 Redis 실시간 예산 상태
- Prometheus 메트릭 엔드포인트
- Glowroot 기반 API·Worker APM
- k6 페이싱 판단 부하 테스트

## 사전 조건

- Windows PowerShell 5.1 이상 또는 PowerShell 7
- Docker Desktop
- Docker Compose
- Gradle 테스트를 직접 실행할 경우 Java 21

스크립트는 프로젝트 루트의 `.env`를 먼저 사용한다. `.env`가 없으면
`.env.example`의 로컬 기본값을 사용한다. 실제 비밀값을 `.env`에 저장하되
Git에는 커밋하지 않는다.

## 가장 빠른 전체 검증

프로젝트 루트에서 실행한다.

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-tests.ps1
.\scripts\run-e2e.ps1
```

현재 PowerShell의 실행 정책을 바꾸고 싶지 않다면 `.cmd` 진입점을 사용한다.

```powershell
.\scripts\run-tests.cmd
.\scripts\run-e2e.cmd
```

`run-e2e.ps1`은 애플리케이션 이미지를 빌드하고 Docker Compose 스택을
시작한 뒤 전체 스모크 테스트를 수행한다. 이미 스택이 실행 중이면 다음과
같이 빌드와 시작을 생략한다.

```powershell
.\scripts\run-e2e.ps1 -SkipStart
```

기존 이미지를 다시 빌드하지 않고 스택만 시작하려면 다음과 같이 실행한다.

```powershell
.\scripts\run-e2e.ps1 -NoBuild
```

## 스크립트 목록

### check-prerequisites.ps1

Docker, Docker Compose, 필수 프로젝트 파일, HMAC 비밀키 길이를 검사한다.

```powershell
.\scripts\check-prerequisites.ps1
```

### start-local.ps1

PostgreSQL, Redis, Kafka, pacing-api, pacing-worker를 시작하고 health check가
성공할 때까지 기다린다.

```powershell
.\scripts\start-local.ps1
.\scripts\start-local.ps1 -Monitoring
.\scripts\start-local.ps1 -Glowroot
.\scripts\start-local.ps1 -NoBuild
```

Glowroot를 처음 연결할 때는 설치 경로를 한 번 지정한다.
Java 21 계측을 위해 Glowroot 0.14.5 이상이 필요하다.

```powershell
.\scripts\start-local.ps1 `
  -Glowroot `
  -GlowrootHome C:\tools\glowroot
```

실행 파일은 Git에서 제외되는 `.glowroot/pacing-api`와
`.glowroot/pacing-worker`에 각각 복제된다. 이후에는 `-Glowroot`만
지정하면 된다. API APM은 `http://localhost:4000`, Worker APM은
`http://localhost:4001`에서 확인한다.

### setup-glowroot.ps1

기존 Glowroot 설치본의 Agent 실행 파일만 API용과 Worker용으로 분리한다.
원본 설치본의 데이터와 로그는 복사하지 않는다.

```powershell
.\scripts\setup-glowroot.ps1 `
  -GlowrootHome C:\tools\glowroot
```

### wait-for-services.ps1

API와 Worker가 이미 시작된 경우 health check만 수행한다.

```powershell
.\scripts\wait-for-services.ps1
.\scripts\wait-for-services.ps1 -Glowroot
```

### stop-local.ps1

컨테이너를 중지한다. 데이터 볼륨은 보존한다.

```powershell
.\scripts\stop-local.ps1
```

### reset-local.ps1

컨테이너와 PostgreSQL, Redis, Kafka 로컬 데이터 볼륨을 삭제한다.
실수로 실행되지 않도록 `-Force`가 필수다.

```powershell
.\scripts\reset-local.ps1 -Force
```

### upsert-campaign.ps1

HMAC ADMIN 권한으로 테스트 캠페인을 등록하거나 변경한다.

```powershell
.\scripts\upsert-campaign.ps1 `
  -CampaignId campaign-local-1 `
  -PacingStrategy ASAP `
  -TotalBudget 100000 `
  -DailyBudgetLimit 50000
```

### update-peak-policy.ps1

동적 피크 시간대와 가중치를 변경한다.

```powershell
.\scripts\update-peak-policy.ps1 `
  -StartTime 18:00:00 `
  -EndTime 23:00:00 `
  -NormalWeight 0.5 `
  -PeakWeight 1.5
```

### request-pacing-decision.ps1

후보 캠페인의 페이싱 판단을 요청한다.

```powershell
.\scripts\request-pacing-decision.ps1 `
  -CampaignId campaign-local-1
```

### reserve-budget.ps1

외부 경매에서 최종 선정됐다고 가정하고 예산을 예약한다.

```powershell
.\scripts\reserve-budget.ps1 `
  -CampaignId campaign-local-1 `
  -ReservationId reservation-local-1 `
  -Amount 1000
```

### publish-billing-event.ps1

예약 ID를 Kafka message key로 사용해 과금 이벤트를 발행한다.

```powershell
.\scripts\publish-billing-event.ps1 `
  -ReservationId reservation-local-1 `
  -EventType CHARGED `
  -ActualAmount 1000
```

`EventType`은 `CHARGED`, `ADJUSTED`, `CANCELLED` 중 하나다.

### inspect-state.ps1

PostgreSQL 예약 상태와 Redis 전체·일일 예산 상태를 함께 출력한다.

```powershell
.\scripts\inspect-state.ps1 `
  -CampaignId campaign-local-1 `
  -ReservationId reservation-local-1
```

### run-e2e.ps1

다음 흐름을 자동 검증한다.

1. ACTIVE ASAP 캠페인 생성
2. 피크 정책 변경·조회
3. 권한 없는 호출, 잘못된 서명, nonce 재사용 차단
4. ASAP 캠페인 PASS 판단
5. 신규 예약과 멱등 재요청
6. 예약 ID 충돌과 일일 예산 초과
7. 예약 PostgreSQL 저장
8. CHARGED 확정과 중복 과금 방지
9. ADJUSTED 보정
10. CANCELLED 취소
11. Redis·PostgreSQL 최종 금액
12. API·Worker Prometheus 메트릭

### run-load-test.ps1

Docker의 k6 이미지를 사용해 페이싱 판단 API에 일정한 요청률을 발생시킨다.
먼저 활성 캠페인을 생성하고 한 번 판단을 호출해 예산 상태를 초기화해야 한다.

```powershell
.\scripts\run-load-test.ps1 `
  -CampaignId campaign-local-1 `
  -Rate 100 `
  -Duration 30s
```

기본 임계치는 실패율 1% 미만, p95 100ms 미만, p99 200ms 미만이다.
더 높은 부하는 `Rate`, `PreAllocatedVUs`, `MaxVUs`로 조정한다.

## 사용자 지정 환경변수 파일

모든 PowerShell 스크립트는 필요한 경우 별도 환경변수 파일을 받을 수 있다.

```powershell
.\scripts\run-e2e.ps1 `
  -EnvironmentFile C:\secure\pacing-local.env
```
