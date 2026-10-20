-- 지각 알림 분리 (디랩 1019 회신).
--
-- 1016 에는 지각을 등원 알림에 합쳐 넣었다. 지각 전용 문구가 없어서였고,
-- 그 문구가 1019 에 왔으므로 되돌린다.

-- ★★ 임계값을 둘로 나눈다
--
-- 지금까지 academy.attendance_deadline 하나가 두 일을 같이 했다:
--     AttendancePolicy.decide(..., lateAfter, ...)       지각 판정
--     AcademyRepository.findActiveByDeadlineBetween()    미등원·결석 배치
--
-- 그런데 디랩이 준 시각이 다르다 — 지각 07:50 / 결석 08:20(1016 회신).
-- 한 칸으로 두면 기준시각이 08:20 인 지점에서 07:55 에 온 학생이
-- 출결상 정상 등원인데 학부모에게는 지각 알림이 간다.
--
-- ⚠️ 미등원 배치는 attendance_deadline 을 계속 본다. late_after 가 아니다
--    (1019 회신: "지금처럼 지점마다 정한 등원 기준시각에 맞춰" — 그 축은 그대로다).
--
-- ★★ NULL 을 허용하고 기본값을 넣지 않는다.
--
--   07:50 을 기본값으로 박으면 **지점별 값이 안 들어온 지점에서 08시에 오는 학생이
--   전부 지각으로 찍힌다.** 지금은 11개 지점이 전부 09:00 이고 디랩이 준 07:50·08:20 은
--   한 건도 들어오지 않았다 — 그 상태로 배포하면 전 지점에서 지각이 양산된다.
--
--   비어 있으면 attendance_deadline 을 쓴다(= 지금 동작 그대로). 값이 들어온 지점만
--   그 값으로 판정한다. 「모르면 추측값을 쓰지 않고 기존 동작을 유지한다」 —
--   단가 미상을 0 으로 채우지 않고, 하원 미입력 순공을 0 으로 두지 않는 것과 같은 판단이다.
ALTER TABLE academy ADD COLUMN late_after TIME;

COMMENT ON COLUMN academy.late_after IS
    '지각 판정 기준 시각. 비어 있으면 attendance_deadline 을 쓴다. 미등원 배치는 항상 attendance_deadline 을 본다';

-- 지각 전용 템플릿.
--
-- ★ content_confirmed = TRUE — 문구가 1019 에 확정돼 들어왔다.
-- ★ review_status = DRAFT — 카카오 등록·심사 전이다. NOT_REQUIRED 로 두면 심사 게이트를
--   그냥 통과해서, 등록도 안 한 템플릿으로 발송을 시도하고 카카오가 거절한다.
INSERT INTO notification_template
    (event_code, channel, recipient_type, title_template, body_template,
     required_variables, content_confirmed, review_status)
VALUES
    ('ATTENDANCE_LATE', 'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생이 {taggedAt}에 지각 등원하였습니다.',
     'studentName,taggedAt', TRUE, 'DRAFT');

-- ⚠️ V20261016110000 의 ATTENDANCE_CHECK_IN 주석이 "지각도 이 템플릿으로 나간다"고
--    적혀 있는데 이제 아니다. 적용된 마이그레이션은 고칠 수 없으므로(체크섬) 여기 남긴다.
COMMENT ON TABLE notification_template IS
    '알림 문구·채널 매핑. 이벤트당 한 행(event_code UNIQUE). 지각은 ATTENDANCE_LATE 로 분리됐다(1019)';
