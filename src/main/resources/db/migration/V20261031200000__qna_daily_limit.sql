-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다.

-- 질의응답 ★ 하루 신청 상한 · ★ 직원 대리 예약 (디랩 1029 8-1)
--
-- ── ① 하루 상한 ───────────────────────────────────────────────
--
-- ★ 지금은 ★ 같은 슬롯 중복만 막는다 — 하루 몇 건이든 잡을 수 있다.
--
-- ★★ 「하루」는 ★ 신청 시각이 아니라 ★ 슬롯 날짜로 센다 (PM 결정).
--    ⚠️ 신청 시각으로 세면 ★ 밤 11시에 내일·모레 것을 몰아 잡을 수 있고,
--    반대로 ★ 같은 날 상담 두 개를 잡아도 ★ 어제 신청했으면 통과한다.
--
-- ★ 상한을 ★ 지점 설정으로 둔다 — ⚠️ 코드에 2를 박지 말 것(1029 지침).
--   지점마다 상담 인력이 달라 ★ 같은 값일 이유가 없다.
--
-- ★ 기본값 2 는 ★ 디랩이 말한 값이다(10/21 질문 문구 「오늘은 이미 2건 신청했습니다」).
--
-- ── ② 직원 대리 예약 ───────────────────────────────────────────
--
-- ★ 관리자 API 에 ★ 예약을 만드는 경로가 없었다 — 개설·마감·수정·삭제뿐이다.
--   ⚠️ 그래서 ★ 전화로 신청한 학생을 직원이 넣어 줄 수 없었다.
--
-- ★ 대리 예약은 ★ 상한을 건너뛴다(1029 지침) — 직원이 사정을 보고 넣는 것이라
--   ★ 상한은 ★ 학생 셀프 신청을 고르게 나누기 위한 것이다.
--   ⚠️ 다만 ★ 같은 슬롯 중복은 ★ 여전히 막는다 — 한 자리에 두 번 앉을 수는 없다.
--
-- ★ 누가 넣었는지 남긴다 — ⚠️ 없으면 ★ 「학생이 직접 잡은 것」과 구분되지 않아
--   상한을 왜 넘겼는지 설명할 수 없다.

ALTER TABLE academy
    ADD COLUMN IF NOT EXISTS qna_daily_limit SMALLINT NOT NULL DEFAULT 2;

COMMENT ON COLUMN academy.qna_daily_limit IS
    '학생이 하루에 신청할 수 있는 질의응답 건수. 슬롯 날짜 기준으로 센다(신청 시각이 '
    '아니다). 직원 대리 예약은 이 상한을 건너뛴다. 기본 2(디랩 1029 8-1).';

ALTER TABLE qna_offline_reservation
    ADD COLUMN IF NOT EXISTS reserved_by_account_id BIGINT;

ALTER TABLE qna_offline_reservation
    ADD CONSTRAINT fk_qna_reservation_reserved_by
        FOREIGN KEY (reserved_by_account_id) REFERENCES account (id);

COMMENT ON COLUMN qna_offline_reservation.reserved_by_account_id IS
    '직원이 대리로 넣은 경우 그 계정. NULL 은 학생이 직접 잡은 것이다 — 이 값이 없으면 '
    '상한을 왜 넘겼는지 설명할 수 없다.';
