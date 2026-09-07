-- V20260907_1100: 사물함 번호 유니크를 부분 인덱스로
--
-- 지금까지 사물함에는 **삭제가 없었다**(생성과 배정만 열려 있었다). 그래서 전체 유니크라도
-- 문제가 드러나지 않았는데, 삭제를 열면서 바로 드러난다:
--
--   L-001 을 지운다 → 화면에서 사라진다 → 다시 만든다 → **제약 위반으로 막힌다**
--
-- 지운 행이 번호를 계속 붙잡고 있어서다. 화면에는 그 사물함이 안 보이니 "없는 걸 못
-- 만든다"는 상태가 된다. room_master(V20260904_1300)·track_master(V20260907_1000)에서
-- 같은 이유로 이미 바꿨다.

ALTER TABLE locker_master DROP CONSTRAINT IF EXISTS uq_locker_master;

CREATE UNIQUE INDEX uq_locker_master_no
    ON locker_master (academy_id, locker_no) WHERE is_deleted = FALSE;
