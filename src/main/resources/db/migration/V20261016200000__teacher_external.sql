-- 외부 강사 (1016 피드백 2-13)
--
-- ★ 강사 마스터를 따로 만들지 않는다. 테이블을 새로 두면 특강·시간표·출석부가
--   두 종류의 강사를 각각 다뤄야 해서 분기가 전부 두 벌이 된다.
--
-- ⚠️ 외부 강사는 로그인 계정이 없다. 그래서 담임으로 지정하면 안 된다 —
--   담임이 곧 방화벽 승인 에스컬레이션 대상이라(approval_request.escalation_teacher_id),
--   로그인 못 하는 사람이 담임이면 학부모 무응답 시 넘길 사람이 없어 승인이 멈춘다.
--   그 검증은 애플리케이션에서 한다(기존 행이 전부 내부 강사라 CHECK 로는 걸 수 없다).
ALTER TABLE teacher ADD COLUMN external    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE teacher ADD COLUMN affiliation VARCHAR(100);

COMMENT ON COLUMN teacher.external IS
    '외부 강사 여부. TRUE 면 로그인 계정이 없고 담임으로 지정할 수 없다';
COMMENT ON COLUMN teacher.affiliation IS '소속(외부 강사). 내부 강사는 비어 있다';
