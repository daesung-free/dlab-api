-- ==========================================================================
-- 알림 템플릿 관리 (실행가이드 P1-12 "CRUD · 심사상태 · 채널 매핑표")
--
-- 템플릿 테이블은 V1에 있고 6종이 seed돼 있다. 여기서 더하는 것은 두 축이다:
--   ① 수신자   — 앱 요구사항 A-C3 "이벤트별 수신자·채널 매핑을 표로 고정"
--   ② 심사상태 — 카카오 알림톡 사전심사(E-5). 심사 리드타임이 길어 진행 상황을 봐야 한다
-- ==========================================================================


-- --------------------------------------------------------------------------
-- ① 수신자 (A-C3 매핑표)
-- --------------------------------------------------------------------------

-- A-C3가 이벤트마다 수신자를 지정한다 — 입·퇴실은 학부모, Daily Report는 학생·학부모,
-- 승인요청은 "학부모 또는 선생님(라우팅)".
--
-- ★ ROUTED를 별도 값으로 둔다. 승인 요청은 수신자가 고정이 아니라
--   approval_item.approver_type에 따라 실행 시점에 갈린다 — 여기 PARENT로 박아두면
--   승인 주체 매트릭스(I-12)가 바뀔 때 템플릿까지 같이 고쳐야 한다.
ALTER TABLE notification_template
    ADD COLUMN recipient_type VARCHAR(20) NOT NULL DEFAULT 'PARENT'
        CHECK (recipient_type IN ('STUDENT', 'PARENT', 'BOTH', 'ROUTED'));

COMMENT ON COLUMN notification_template.recipient_type IS
    'A-C3 매핑표의 수신자 축. ROUTED는 승인 주체 설정에 따라 실행 시점에 갈린다는 뜻이다.';

-- seed된 6종을 A-C3 매핑표에 맞춘다.
UPDATE notification_template SET recipient_type = 'ROUTED'
 WHERE event_code = 'APPROVAL_REQUEST_CREATED';


-- --------------------------------------------------------------------------
-- ② 카카오 알림톡 사전심사 상태 (E-5)
-- --------------------------------------------------------------------------

-- ★ content_confirmed(운영팀 문구 확정)와 다른 축이다.
--   문구가 확정돼도 카카오 심사를 통과하지 못하면 알림톡은 못 나간다.
--   한 컬럼으로 합치면 "문구는 정해졌는데 심사 대기 중"을 표현할 수 없고,
--   심사가 리드타임이 길어(E-5) 그 상태로 한참 머문다.
ALTER TABLE notification_template
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK (review_status IN ('NOT_REQUIRED', 'DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'));

-- 심사 반려 사유. 반려되면 문구를 고쳐 재제출해야 하는데, 사유가 없으면 뭘 고칠지 모른다.
ALTER TABLE notification_template ADD COLUMN review_note VARCHAR(500);
ALTER TABLE notification_template ADD COLUMN reviewed_at TIMESTAMPTZ;

COMMENT ON COLUMN notification_template.review_status IS
    '카카오 알림톡 사전심사(E-5) 상태. FCM은 심사가 없어 NOT_REQUIRED로 둔다.
     content_confirmed(운영팀 문구 확정)와 별개 축이다 — 문구가 정해져도 심사를 못 통과하면 못 보낸다.';

-- 알림톡 템플릿만 심사 대상이다. FCM은 NOT_REQUIRED 그대로 둔다.
UPDATE notification_template SET review_status = 'DRAFT' WHERE channel = 'KAKAO_ALIMTALK';

-- 관리 화면이 "심사 대기 중인 것"을 뽑는다. 전체 대비 적은 편이라 부분 인덱스가 맞다.
CREATE INDEX idx_notification_template_review
    ON notification_template (review_status)
 WHERE review_status IN ('DRAFT', 'SUBMITTED', 'REJECTED');
