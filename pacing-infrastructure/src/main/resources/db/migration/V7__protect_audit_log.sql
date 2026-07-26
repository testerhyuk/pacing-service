CREATE OR REPLACE FUNCTION reject_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log rows are append-only';
END;
$$;

CREATE TRIGGER trg_audit_log_reject_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_log_mutation();

CREATE TRIGGER trg_audit_log_reject_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_log_mutation();
