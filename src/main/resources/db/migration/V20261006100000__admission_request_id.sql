-- V20261006100000: 홈페이지 입학예약 — 신청 고유번호(멱등키)
--
-- B안(REST) 수신에 쓴다. 홈페이지가 신청마다 번호를 하나 붙여 보내고, 전송이 실패해
-- 다시 보내면 같은 번호가 온다. 그 번호로 한 건만 남긴다.
--
-- ★ 이것이 없으면 재전송이 곧 중복 접수다. 한 지원자가 두 명으로 보이고, 나중에
--   어느 쪽이 진짜인지 가릴 방법이 없다. 재전송 기능을 넣는 순간 반드시 필요하다.
--
-- ★ NULL 을 허용한다. A안(기존 DSA 방식)으로 들어온 건에는 이 번호가 없다.
--   부분 유니크라 NULL 은 제약을 타지 않는다.

ALTER TABLE admission_reservation ADD COLUMN request_id VARCHAR(64);

CREATE UNIQUE INDEX uq_admission_reservation_request
    ON admission_reservation (request_id)
    WHERE request_id IS NOT NULL;

COMMENT ON COLUMN admission_reservation.request_id IS
    '홈페이지가 붙인 신청 고유번호(멱등키). 같은 번호가 다시 오면 저장하지 않고 기존 건을 돌려준다';
