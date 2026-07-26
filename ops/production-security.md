# 운영 보안 적용

- API는 `prod` 프로필로 실행하고 TLS 키 저장소를 Secret으로 주입한다.
- PostgreSQL migration 계정, API 계정, Worker 계정을 분리한다.
  migration 후 `postgres-least-privilege.sql`을 DB 소유자로 실행한다.
- Redis는 `pacing-api`, `pacing-worker` ACL 사용자를 따로 만들고 각
  애플리케이션에 해당 사용자명과 비밀번호만 주입한다.
- Kafka는 TLS 클라이언트 인증을 사용하고 Worker 인증서 주체에는
  과금 토픽 consume, retry/DLT 토픽 produce 권한만 부여한다.
- HMAC 이전 키에는 반드시 만료 시각을 함께 설정한다. 만료된 키는
  서명 검증 후보에서 자동으로 제외된다.
- 운영 Secret, 인증서, keystore는 저장소에 커밋하지 않는다.
