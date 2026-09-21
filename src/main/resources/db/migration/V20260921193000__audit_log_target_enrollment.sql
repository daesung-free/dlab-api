-- 수정 이력의 대상 학생 (관리자 웹 "금일 수정 이력" 대상 이름·학번)
--
-- 벌점·사유신청·청구·성적은 학생(등록 건)에 붙는데, 이력에는 그 행의 id 만 남아
-- "누구 벌점을 지웠나" 를 보려면 행을 하나씩 따라가야 했다. 기록 시점에 등록 건 id 를 같이 남긴다.
-- 이름·학번은 저장하지 않는다 — 개인정보가 이력으로 복제되고, 조회 시점에 붙이면 된다.
-- 기존 행은 비어 있다.
ALTER TABLE audit_log
    ADD COLUMN target_enrollment_id BIGINT;

CREATE INDEX idx_audit_log_target_enrollment ON audit_log (target_enrollment_id)
    WHERE target_enrollment_id IS NOT NULL;
