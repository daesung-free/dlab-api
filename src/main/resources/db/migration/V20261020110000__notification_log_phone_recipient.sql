-- 계정 없는 수신자에게도 알림을 보낼 수 있게 한다.
--
-- ★★ 왜 필요한가 — 계정이 없는 수신자가 둘 있고 둘 다 확정된 요구다
--
--   ① 입학 대기자 (디랩 1019, 7번 대기자 안내톡)
--      admission_reservation 은 "★ 학생이 아니다" — 계정도 student 행도 없고
--      전화번호만 컬럼으로 들고 있다(student_tel / parent_tel).
--   ② 급식업체 (0826 확정 — 환불정보를 업체에 알림톡으로 즉시 발송)
--      업체는 사업자라 계정이 없다.
--
--   recipient_account_id 가 NOT NULL 이어서 둘 다 기존 발송 경로를 아예 탈 수 없었다.
--
-- ★ 전용 발송 경로를 따로 만들지 않는다. 그러면 발송 이력이 두 곳으로 갈라져
--   「왜 안 갔나」에 답할 수 없다 — 이 프로젝트에서 이력이 한 곳에 모여야 하는 이유는
--   화면·로그 어디에도 증거가 안 남는 실패를 반복해서 겪었기 때문이다.
--
-- ⚠️ 둘 중 하나는 반드시 있어야 한다. 둘 다 비면 「누구에게 보내려 했는지」를 잃는다.
ALTER TABLE notification_log ALTER COLUMN recipient_account_id DROP NOT NULL;
ALTER TABLE notification_log ADD COLUMN recipient_phone VARCHAR(20);
ALTER TABLE notification_log ADD COLUMN recipient_name  VARCHAR(50);

ALTER TABLE notification_log
    ADD CONSTRAINT ck_notification_recipient
    CHECK (recipient_account_id IS NOT NULL OR recipient_phone IS NOT NULL);

COMMENT ON COLUMN notification_log.recipient_phone IS
    '계정 없는 수신자의 번호(입학 대기자·급식업체). 계정이 있으면 NULL 이고 계정에서 읽는다';
COMMENT ON COLUMN notification_log.recipient_name IS
    '계정 없는 수신자의 표시 이름. 이력 화면에서 「누구에게 갔나」를 보여주는 데 쓴다';

-- 번호로 보낸 이력도 찾을 수 있어야 한다 — "그 번호로 뭐가 갔나" 는 실제로 들어오는 문의다
CREATE INDEX idx_notification_recipient_phone
    ON notification_log (recipient_phone, created_at DESC)
    WHERE recipient_phone IS NOT NULL;
