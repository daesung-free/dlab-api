-- 출결 태깅 알림 5종 (디랩 1016 문구 1~5번).
--
-- ⚠️ 파일명이 14자리인 이유: 밑줄 방식(V20261016_1100)은 Flyway 가 `20261016.1100` 으로
--    파싱해 **이미 적용된 `20261014200000` 보다 작아진다.** 운영은 out-of-order 를 막아
--    배포가 실패한다.
--
-- ★ review_status 를 DRAFT 로 둔다 — 문구는 확정됐지만 **카카오에 등록·심사 전**이다.
--   NOT_REQUIRED(기본값) 로 두면 심사 게이트를 그냥 통과해서, 등록도 안 한 템플릿으로
--   발송을 시도하고 카카오가 거절한다. 실제 등록은 알리고 template/add·request API 로 한다.
--
-- ★ content_confirmed 는 TRUE 다. 문구가 실제로 확정돼 들어왔고, 이게 FALSE 면
--   「문구 미확정」으로 걸러져 **심사 통과 뒤에도 안 나간다.**
INSERT INTO notification_template
    (event_code, channel, recipient_type, title_template, body_template,
     required_variables, content_confirmed, review_status)
VALUES
    -- ★ 지각도 이 템플릿으로 나간다. 지각 전용 문구가 없는데 빼면, 기준시각에 미등원
    --   알림을 받은 학부모가 자녀를 **결석으로 알고 끝낸다**.
    ('ATTENDANCE_CHECK_IN',    'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생이 {taggedAt}에 등원했습니다.',
     'studentName,taggedAt', TRUE, 'DRAFT'),

    ('ATTENDANCE_CHECK_OUT',   'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생이 {taggedAt}에 하원했습니다.',
     'studentName,taggedAt', TRUE, 'DRAFT'),

    -- 사유 외출도 이 템플릿이다. 사유 제출 여부는 벌점·집계의 구분이고,
    -- 학부모에게는 「나갔다」는 사실이 같다
    ('ATTENDANCE_OUTING',      'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생이 {taggedAt}에 외출하였습니다.',
     'studentName,taggedAt', TRUE, 'DRAFT'),

    ('ATTENDANCE_RETURN',      'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생이 {taggedAt}에 외출 복귀 하였습니다.',
     'studentName,taggedAt', TRUE, 'DRAFT'),

    ('ATTENDANCE_EARLY_LEAVE', 'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생이 {taggedAt}에 조퇴 하였습니다.',
     'studentName,taggedAt', TRUE, 'DRAFT');

-- ⚠️ 카카오 등록 시 변수 표기는 `#{변수명}` 이다. 위 `{변수명}` 은 우리 렌더러 형식이고,
--    알리고 template/add 에 올릴 때 변환한다 — 두 형식을 섞어 두면 치환이 안 된 문구가 나간다.
