-- V20260806_1000: 학생 앱 온보딩 상태 (앱 요구사항 A-2)
--
-- ★ 서버의 이 값이 단일 진실이다. 앱은 여기 따라 화면을 분기한다(시트 A-2 명시).
--
-- ★ 승인 상태(PENDING_APPROVAL·APPROVED)를 여기 두지 않는다.
--   account.status(PENDING → ACTIVE)가 이미 같은 사실을 들고 있어서, 두 곳에 두면
--   어긋났을 때 어느 쪽이 맞는지 알 수 없다. 두 축을 함께 보면 상태가 하나로 정해진다:
--     account.status=PENDING + REGISTERED  → 승인 대기
--     account.status=ACTIVE  + REGISTERED  → 승인됨, OT 전
--
-- ★ 등록 건이 아니라 사람(student)에 붙인다 — 앱 계정(account.student_id)이 사람에 붙기 때문.

ALTER TABLE student
    ADD COLUMN onboarding_status VARCHAR(20) NOT NULL DEFAULT 'REGISTERED'
        CHECK (onboarding_status IN
               ('REGISTERED', 'OT_DONE', 'PARENT_LINKED', 'SCHEDULE_SET', 'ACTIVE'));

COMMENT ON COLUMN student.onboarding_status IS
    '앱 온보딩 단계(A-2). 승인 여부는 account.status가 따로 관리한다 — 여기 중복해서 넣지 말 것';

-- 온보딩 미완료자 추출(독려 발송·진행현황 모니터링, F-4.12-1)이 잦다
CREATE INDEX idx_student_onboarding
    ON student (onboarding_status)
    WHERE onboarding_status <> 'ACTIVE' AND NOT is_deleted;
