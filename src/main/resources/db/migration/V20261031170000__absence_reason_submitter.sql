-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다 — ★ 14자리 파일이 만든
--   테이블(absence_reason · account)을 참조하므로 ★ 순서가 중요하다.
-- 사유 신청에 ★ 누가 냈는지 남긴다 (디랩 1031 ③ · PM 결정)
--
-- ★★ 학부모도 앱에서 사유를 낼 수 있게 된다.
--
--    결석 안내 알림톡이 ★ 보호자에게만 가는데(UM_8236) 문구가
--    *「사유가 있으시면 어플에서 사유 입력을 부탁드립니다」* 다. 그런데 지금은
--    ★ 학생만 낼 수 있어(requireStudent) ★ 받은 사람이 할 수 없는 일을 안내하고 있었다.
--
-- ★ 관리자 화면에서 ★ 「학부모 신청」이 구분돼 보여야 한다(디랩 요청).
--
-- ⚠️ 기존 행은 전부 학생이 낸 것이지만 ★ STUDENT 로 채우지 않고 NULL 로 둔다.
--    관리자 직접 등록분(데스크)이 섞여 있어서다 — 그것까지 「학생이 냈다」로 바뀌면
--    ★ 화면이 거짓을 말한다. NULL 은 「이 값이 생기기 전의 신청」을 뜻한다.

ALTER TABLE absence_reason
    ADD COLUMN IF NOT EXISTS submitter_type VARCHAR(10),
    ADD COLUMN IF NOT EXISTS submitted_by_account_id BIGINT;

ALTER TABLE absence_reason
    ADD CONSTRAINT fk_absence_reason_submitted_by
        FOREIGN KEY (submitted_by_account_id) REFERENCES account (id);

ALTER TABLE absence_reason
    ADD CONSTRAINT absence_reason_submitter_type_check
        CHECK (submitter_type IS NULL OR submitter_type IN ('STUDENT', 'GUARDIAN', 'STAFF'));

COMMENT ON COLUMN absence_reason.submitter_type IS
    '누가 냈는가. STUDENT(학생 앱) / GUARDIAN(학부모 앱) / STAFF(데스크 직접 등록). '
    'NULL 은 이 값이 생기기 전의 신청이다 — 학생분과 데스크분이 섞여 있어 채우지 않는다.';

COMMENT ON COLUMN absence_reason.submitted_by_account_id IS
    '낸 계정. STAFF 는 직원 계정이고, 관리자 등록 경로에서는 비어 있을 수 있다.';
