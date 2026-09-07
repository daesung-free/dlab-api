-- V20260904_1300: 강의실 마스터 보완 (F-4.10-1 기초관리 "강의실")
--
-- 테이블 자체는 V2 에서 이미 만들었다. 그 위 엔티티·API 가 없어 화면이 못 쓰던 것이라
-- 여기서는 두 가지만 고친다.
--
-- ★ 1. UNIQUE 를 부분 인덱스로 바꾼다.
--   지금은 (academy_id, room_no) 전체 유니크라 **soft delete 된 행까지 자리를 차지한다.**
--   201호를 지웠다가 다시 만들면 제약 위반으로 막히는데, 화면에는 그 강의실이 안 보이니
--   "없는 방을 못 만든다"는 상태가 된다. 다른 마스터가 전부 부분 인덱스인 이유와 같다.
--
-- ★ 2. memo · active 를 더한다.
--   code 는 두지 않는다 — room_no("201")가 이미 그 역할이고, 둘을 같이 두면
--   데스크가 어느 쪽으로 방을 부르는지 갈린다.
--   active 는 필요하다: 공사·용도변경으로 한동안 못 쓰는 방이 생기는데, 삭제하면
--   그 방에서 진행됐던 특강 기록의 근거가 끊긴다.

ALTER TABLE room_master DROP CONSTRAINT IF EXISTS uq_room_master;

CREATE UNIQUE INDEX uq_room_master_no
    ON room_master (academy_id, room_no) WHERE is_deleted = FALSE;

ALTER TABLE room_master ADD COLUMN memo   VARCHAR(200);
ALTER TABLE room_master ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN room_master.active IS
    'FALSE = 배정·선택 불가(공사 등). 그 방에서 진행된 과거 기록은 남는다';
