-- 「다른 사람입니다」 판단 기록 (2026-10-13).
--
-- ★★ 이 판단을 남길 곳이 없어서 **같은 조합이 볼 때마다 다시 뜬다.**
--    중복 후보는 이름이 같으면 계속 걸리므로, 한 번 「다른 사람」으로 정리한 쌍이
--    다음에 또 올라오면 담당자가 **매번 같은 판단을 다시** 해야 한다.
--
-- ★ 판단은 **「예약 ↔ 등록 건」 쌍**으로 남긴다. 이름·생년월일 같은 값으로 남기면
--   ① 개인정보가 세 번째 테이블에 또 쌓이고 ② 연락처가 바뀌는 순간 기록이 안 맞는다.
--   id 쌍은 바뀌지 않는다.
--
-- ⚠️ 신규접수 경로는 **판단 시점에 등록 건이 아직 없다**(저장 직전에 막는 구조다).
--    그래서 등록이 끝난 뒤에 쌍을 적는다 — 그 한 번이 지나면 이후 전환·목록에서
--    같은 쌍이 걸러진다.
--
-- ⚠️ 파일명이 14자리인 이유: 밑줄 방식(`V20261013_2200`)은 `20261013.2200` 으로 파싱돼
--    **이미 적용된 `20261013200000`(순찰 메뉴)보다 작아진다.** 운영은 out-of-order 를
--    막으므로 배포가 실패한다.
CREATE TABLE admission_duplicate_decision
(
    id             BIGSERIAL PRIMARY KEY,
    year           SMALLINT    NOT NULL,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    reservation_id BIGINT      NOT NULL REFERENCES admission_reservation (id),
    enrollment_id  BIGINT      NOT NULL REFERENCES student_enrollment (id),
    -- 어느 화면에서 내린 판단인지. 신규접수(ADMIT) 와 전환(CONVERT) 이 서로 다른 흐름이라
    -- 나중에 "어디서 잘못 넘어갔나" 를 되짚으려면 구분이 필요하다
    decided_on     VARCHAR(10) NOT NULL CHECK (decided_on IN ('ADMIT', 'CONVERT')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 쌍을 두 번 적지 않는다. 두 번 눌러도 한 행이면 "몇 번 판단했나" 가 흐려지지 않는다
CREATE UNIQUE INDEX uq_admission_duplicate_decision
    ON admission_duplicate_decision (reservation_id, enrollment_id)
    WHERE NOT is_deleted;

CREATE INDEX idx_admission_duplicate_decision_reservation
    ON admission_duplicate_decision (reservation_id) WHERE NOT is_deleted;

COMMENT ON TABLE admission_duplicate_decision IS
    '입학예약과 등록 건이 다른 사람이라고 담당자가 확인한 기록. 다음 탐지에서 그 쌍을 거른다';
COMMENT ON COLUMN admission_duplicate_decision.created_by IS
    '판단한 담당자. 누가 넘겼는지가 남아야 나중에 되짚을 수 있다';
