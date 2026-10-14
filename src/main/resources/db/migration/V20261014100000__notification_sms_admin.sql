-- 문자 채널 · 관리자 수신자 + 승인 알림 `requestType` 변수 (2026-10-14 디랩 회신).
--
-- ★ 세 가지가 같은 사건에서 나왔다 — **긴급출입 알림이 문자로, 관리자에게 나간다**.
--   기존 값으로는 둘 다 표현되지 않아 CHECK 제약이 막는다.
--
-- ⚠️ 파일명이 14자리인 이유: 밑줄 방식(`V20261014_1000`)은 `20261014.1000` 으로 파싱돼
--    **이미 적용된 `20261013220000` 보다 작아진다.** 운영은 out-of-order 를 막아 배포가 실패한다.

-- ① 문자 채널.
--    ★ 알림톡과 다른 점은 **심사가 없다**는 것이다 — 본문을 그대로 보내므로
--      `review_status = NOT_REQUIRED` 로 두면 바로 나간다.
ALTER TABLE notification_template DROP CONSTRAINT IF EXISTS notification_template_channel_check;
ALTER TABLE notification_template
    ADD CONSTRAINT notification_template_channel_check
        CHECK (channel IN ('KAKAO_ALIMTALK', 'SMS', 'FCM_PUSH'));

-- ② 관리자 수신자.
--    ⚠️ `ROUTED` 로 때우면 안 된다 — 그건 **승인 주체 설정**을 따라가는 값이라
--      학생이 「학부모 우선」을 골라 두면 **관리자 알림이 학부모에게 간다.**
ALTER TABLE notification_template DROP CONSTRAINT IF EXISTS notification_template_recipient_type_check;
ALTER TABLE notification_template
    ADD CONSTRAINT notification_template_recipient_type_check
        CHECK (recipient_type IN ('STUDENT', 'PARENT', 'BOTH', 'ROUTED', 'ADMIN'));

-- ③ 승인 요청 알림에 「무엇을 승인하는지」를 더한다.
--
-- ★★ **카카오 템플릿은 등록한 뒤 변수를 더하면 재심사다.** 그래서 올리기 전인 지금 넣는다 —
--     나중이면 승인 대기가 며칠 늘어난다.
--
-- ⚠️ `required_variables` 는 **누락 검사의 기준**이다. 여기에 넣고 서비스가 값을 안 채우면
--    그 알림이 통째로 `SKIPPED("변수 누락")` 이 된다 — 코드와 같은 커밋에서 바꿔야 하는 이유다.
UPDATE notification_template
SET required_variables = CASE
        WHEN required_variables IS NULL OR required_variables = ''
            THEN 'requestType'
        WHEN required_variables LIKE '%requestType%'
            THEN required_variables
        ELSE 'requestType,' || required_variables
    END
WHERE event_code = 'APPROVAL_REQUEST_CREATED';

COMMENT ON COLUMN notification_template.channel IS
    '발송 채널. SMS 는 심사가 없어 review_status=NOT_REQUIRED 로 바로 나간다';
COMMENT ON COLUMN notification_template.recipient_type IS
    '수신 대상. ADMIN 은 지점 담당자(긴급출입 등) — 누가 받을지는 메뉴 권한으로 좁힌다';
