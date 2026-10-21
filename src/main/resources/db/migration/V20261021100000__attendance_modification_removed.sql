-- 출결 태깅 "삭제" 이력.
--
-- 지금까지 정정 이력은 추가(addedEvent/addedAt)와 상태 정정(before/after)뿐이라
-- "무엇을 지웠는가"를 남길 자리가 없었다. 디랩 1021 피드백 2-1:
--   "실수로 처리된 항목은 삭제도 필요할것 같습니다."
--
-- ★ 컬럼을 새로 두는 이유 — added* 를 재사용하면 추가와 삭제가 구분되지 않는다.
--   같은 칸에 넣고 플래그로 가르면 기존 행을 전부 backfill 해야 하고,
--   "그 행이 추가였는지 삭제였는지"가 플래그 하나에 걸린다.
ALTER TABLE attendance_modification
    ADD COLUMN removed_event VARCHAR(20),
    ADD COLUMN removed_at    TIMESTAMPTZ;

COMMENT ON COLUMN attendance_modification.removed_event IS
    '삭제한 태깅의 이벤트. 추가(added_event)와 구분하기 위해 별도 컬럼이다';
COMMENT ON COLUMN attendance_modification.removed_at IS
    '삭제한 태깅의 기록 시각. 언제 지웠는가가 아니라 어느 태깅을 지웠는가다';
