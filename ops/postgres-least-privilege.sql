-- pacing_api와 pacing_worker LOGIN 역할은 비밀 관리 시스템을 통해
-- 미리 생성한다. 이 파일은 migration 완료 후 DB 소유자로 실행한다.

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;

GRANT USAGE ON SCHEMA public TO pacing_api, pacing_worker;

GRANT SELECT, INSERT, UPDATE
    ON campaign, peak_policy
    TO pacing_api;
GRANT SELECT, INSERT
    ON budget_reservation
    TO pacing_api;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON pacing_state_snapshot
    TO pacing_api;
GRANT INSERT
    ON audit_log
    TO pacing_api;
GRANT USAGE, SELECT
    ON SEQUENCE audit_log_id_seq
    TO pacing_api;

GRANT SELECT
    ON campaign
    TO pacing_worker;
GRANT SELECT, INSERT, UPDATE
    ON budget_reservation, billing_event
    TO pacing_worker;
GRANT INSERT
    ON budget_reconciliation
    TO pacing_worker;
GRANT USAGE, SELECT
    ON SEQUENCE budget_reconciliation_id_seq
    TO pacing_worker;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM PUBLIC;
