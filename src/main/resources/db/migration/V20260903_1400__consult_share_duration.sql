-- 상담 일지 — 학부모 공유 범위 · 소요시간 (API_GAPS 5-2 · 5-3)
--
-- 화면 하단 안내가 "학부모 앱에 즉시 반영된다"인데 무엇을 공유할지 정할 자리가 없었다.
-- 공유 범위를 안 두면 상담 내용 전체가 그대로 학부모에게 열린다 — 학생과 나눈 말을
-- 그대로 전달하면 안 되는 상담이 실제로 있다.
--
-- 기본값을 NONE 으로 둔다. 기존 행은 "공유하기로 결정한 적이 없는" 상태이므로
-- 공유로 열어두면 지금까지 쓴 상담이 한꺼번에 학부모에게 보인다.
ALTER TABLE consult_log
    ADD COLUMN parent_share VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN duration_minutes SMALLINT;

ALTER TABLE consult_log
    ADD CONSTRAINT ck_consult_log_parent_share
        CHECK (parent_share IN ('NONE', 'SUMMARY', 'FULL'));

-- 소요시간은 place_note("20분 · 상담실 2")에 섞여 있던 것을 숫자로 뺀 것이다.
-- 문자열이면 "평균 상담 시간"을 셀 수 없다. place_note 는 장소 메모로 그대로 둔다.
ALTER TABLE consult_log
    ADD CONSTRAINT ck_consult_log_duration
        CHECK (duration_minutes IS NULL OR (duration_minutes > 0 AND duration_minutes <= 600));

COMMENT ON COLUMN consult_log.parent_share IS '학부모 공유 범위 — NONE 안 함 / SUMMARY 요약본 / FULL 전체';
COMMENT ON COLUMN consult_log.duration_minutes IS '상담 소요시간(분). 집계용이라 숫자로 둔다';
