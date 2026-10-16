-- 설문 알림 2종 (F-4.6 "미응답자 독려").
--
-- ⚠️ 파일명이 14자리인 이유: 밑줄 방식(V20261016_2100)은 `20261016.2100` 으로 파싱돼
--    이미 적용된 `20261016200000` 보다 작아진다 → out-of-order. 운영은 이를 막아 배포가 실패한다.
--
-- ★★ content_confirmed 를 FALSE 로 둔다 — 디랩에서 설문 알림 문구를 받지 못했다.
--    아래 문구는 **자리를 잡기 위한 임시값**이고, FALSE 이므로 dispatch 가
--    markSkipped("문구 미확정") 으로 거른다. 즉 발송 경로는 완성되지만 실제로는 안 나간다.
--    ⚠️ 문구를 받기 전에 TRUE 로 바꾸지 말 것 — 임시 문구가 학생에게 그대로 나간다.
--
-- ★ review_status 는 DRAFT 다 — 카카오에 등록·심사 전이다. NOT_REQUIRED 로 두면
--   심사 게이트를 통과해 등록도 안 한 템플릿으로 발송을 시도하고 카카오가 거절한다.
--
-- ⚠️ 채널이 알림톡인 이유: 설문은 전 지점이면 수천 건이라 건당 과금이 그대로 늘어난다.
--   FCM 으로 보내면 공짜지만 수신 설정을 끈 학생에게 도달하지 않는다. 중요도 판단은
--   디랩 회신 대기이고, 문구가 올 때 함께 정한다.
INSERT INTO notification_template
    (event_code, channel, recipient_type, title_template, body_template,
     required_variables, content_confirmed, review_status)
VALUES
    ('SURVEY_ASSIGNED', 'KAKAO_ALIMTALK', 'STUDENT', '',
     '[DLab] {studentName} 학생, 「{surveyTitle}」 설문이 도착했습니다. {closesAt}까지 응답해 주세요.',
     'studentName,surveyTitle,closesAt', FALSE, 'DRAFT'),

    -- 「아직 안 내셨습니다」로 시작해야 한다. 첫 발송과 같은 문구면 받은 사람이
    -- 자기가 뭘 놓쳤다고 생각한다
    ('SURVEY_REMINDER', 'KAKAO_ALIMTALK', 'STUDENT', '',
     '[DLab] {studentName} 학생, 「{surveyTitle}」 설문에 아직 응답하지 않으셨습니다. {closesAt}까지 응답해 주세요.',
     'studentName,surveyTitle,closesAt', FALSE, 'DRAFT');
