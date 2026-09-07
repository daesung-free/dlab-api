-- 금일 수정 이력 = 감사 로그 (F-C-1)
--
-- DSA 기능확인 회신에서 클라이언트가 "사용"에 체크한 항목이다(메모/기타 9건 중 이것만).
--
-- ■ created_by 로는 답이 안 된다
--
-- created_by 는 updatable=false 라 최초 작성자만 남는다. "누가 이 학생 벌점을 지웠나"에
-- 답하려면 변경 시점마다 행이 생겨야 한다. 그리고 CLAUDE.md §7 이 경고하듯
-- 이건 나중에 붙여도 그 이전 기간을 복구할 수 없다.
--
-- ■ 값을 통째로 복사하지 않는다
--
-- 바뀐 필드만 남긴다. 전체 스냅샷을 뜨면 개인정보가 이력 테이블로 통째 복제되고,
-- 지운 값이 여기 영구히 남는다(지점 설정 이력에서 같은 판단을 했다).
-- 비밀번호 해시·키오스크 시크릿처럼 남기면 안 되는 필드는 값 대신 "***" 로 들어간다.
--
-- ■ 전 테이블을 켜지 않는다
--
-- 출결 태깅 로그처럼 원래 append-only 인 것까지 켜면 로그가 원장보다 커진다.
-- 엔티티에 @Audited 를 붙인 것만 남긴다 — 켜는 쪽이 명시적으로 고른다.
CREATE TABLE audit_log (
    id            BIGSERIAL   PRIMARY KEY,

    entity_type   VARCHAR(60) NOT NULL,
    entity_id     BIGINT      NOT NULL,
    -- CREATE / UPDATE / DELETE. soft delete 는 DELETE 로 기록한다 —
    -- is_deleted 만 바뀐 UPDATE 로 남기면 화면에서 삭제를 구분할 수 없다
    action        VARCHAR(10) NOT NULL,

    -- 지점 필터용. 엔티티가 지점을 모르면 NULL 이다(마스터·전 지점 공통)
    academy_id    BIGINT,

    actor_id      BIGINT      NOT NULL,
    -- 사람이 읽을 이름. 계정이 지워져도 "누가"가 남아야 한다
    actor_name    VARCHAR(50),
    actor_ip      VARCHAR(45),

    -- [{"field":"points","before":"5","after":"3"}, ...]
    changes       TEXT,

    occurred_at   TIMESTAMPTZ NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 화면이 "오늘 수정된 것"을 먼저 연다
CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_academy  ON audit_log (academy_id, occurred_at DESC);
-- "이 학생 기록이 어떻게 바뀌었나"
CREATE INDEX idx_audit_log_entity   ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_log_actor    ON audit_log (actor_id, occurred_at DESC);

COMMENT ON TABLE audit_log IS
    '감사 로그(F-C-1). @Audited 를 붙인 엔티티의 변경만 남는다';
COMMENT ON COLUMN audit_log.changes IS
    '바뀐 필드만. 민감 필드는 값 대신 *** — 전체 스냅샷을 뜨면 개인정보가 복제된다';
