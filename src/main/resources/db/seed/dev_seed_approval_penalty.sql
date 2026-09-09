-- 로컬 개발용 시드 — 승인정책 · 상벌점 항목 · 출결 태깅 · 보호자. **운영 금지.**
--
-- ★ 왜 필요한가 (프론트 보고 2026-09-02)
--   이게 없으면 화면이 "연동이 안 된 건지 데이터가 없는 건지" 구분되지 않는다:
--     · approval_item 0건  → 사유신청 등록 자체가 APPROVAL_ITEM_NOT_FOUND 로 막힌다
--     · penalty_item 0건   → 상벌점 목록·부여 드롭다운이 빈다
--     · 태깅 로그 0건      → 출결이 전원 ABSENT, 등하원·순공시간이 전부 0
--     · 보호자 0건         → 학부모 연락처 컬럼이 전량 null, 승인도 못 받는다
--
-- ★ 파일명에 V 접두사를 붙이지 않는다 — dev_seed.sql 머리말과 같은 이유다.
--
-- 실행: ./scripts/seed-local.sh   (dev_seed.sql 뒤에 온다 — 학생이 있어야 붙는다)
--
-- 멱등이다. 여러 번 돌려도 중복으로 쌓이지 않는다.
BEGIN;

-- ─────────────────────────────────────────────────────────────
-- 1. 승인 정책 (approval_item)
--
-- ⚠️ 승인 주체는 아직 미확정이다(I-12, 최우선·미해결).
--    확정된 건 방화벽(학부모 1차 → 10분 후 담당선생님)뿐이고, 나머지 둘은
--    화면을 띄우기 위한 잠정값이다. 확정되면 관리자 화면에서 바꾸면 된다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO approval_item (academy_id, year, request_type, approver_type,
                           timeout_minutes, escalation_approver_type)
SELECT a.id, 2026, t.request_type, t.approver_type, t.timeout_minutes, t.escalation
FROM academy a
CROSS JOIN (VALUES
    -- 확정분: 학부모 우선, 10분 무응답 시 담당선생님 (CLAUDE.md §3)
    ('FIREWALL_UNLOCK',  'PARENT', 10::smallint, 'TEACHER'),
    -- 잠정: I-12 대기
    ('ABSENCE_REASON',   'PARENT', 10::smallint, 'TEACHER'),
    ('REGULAR_SCHEDULE', 'PARENT', 10::smallint, 'TEACHER')
) AS t(request_type, approver_type, timeout_minutes, escalation)
WHERE NOT EXISTS (
    SELECT 1 FROM approval_item ai
    WHERE ai.academy_id = a.id AND ai.year = 2026
      AND ai.request_type = t.request_type AND ai.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 2. 상벌점 항목 (penalty_item)
--
-- ⚠️ 점수는 잠정값이다. 클라이언트가 "점수 생성 페이지에서 직접 입력"으로 답했고
--    실제 표는 아직 없다 — 화면에 드롭다운이 뜨게 하려는 목적뿐이다.
--
-- ★ 벌점은 음수로 저장된다. 통계가 부호로 상점·벌점을 가르기 때문에,
--   양수로 넣으면 그 학생의 벌점이 상점으로 집계된다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO penalty_item (academy_id, year, item_name, point_value, category)
SELECT a.id, 2026, t.item_name, t.point_value, t.category
FROM academy a
CROSS JOIN (VALUES
    ('지각',            -2, 'DEMERIT'),
    ('무단결석',        -5, 'DEMERIT'),
    ('무단조퇴',        -3, 'DEMERIT'),
    ('전자기기 적발',   -5, 'DEMERIT'),
    ('데일리테스트 미제출', -2, 'DEMERIT'),
    ('데일리테스트 만점',  3, 'MERIT'),
    ('개근',             5, 'MERIT'),
    ('봉사',             3, 'MERIT')
) AS t(item_name, point_value, category)
WHERE NOT EXISTS (
    SELECT 1 FROM penalty_item pi
    WHERE pi.academy_id = a.id AND pi.year = 2026
      AND pi.item_name = t.item_name AND pi.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 3. 자동부여 규칙 (penalty_rule)
--
-- ★ active = FALSE 로 넣는다. 규칙이 켜진 채로 들어가면 태깅 시드가 들어갈 때
--   전교생에게 벌점이 뿌려진다. 화면에서 토글을 확인하는 것이 목적이다.
--
-- ⚠️ 트리거→점수 매핑은 미확정이다(I-5).
-- ─────────────────────────────────────────────────────────────
INSERT INTO penalty_rule (academy_id, year, trigger_type, trigger_condition, penalty_item_id, active)
SELECT pi.academy_id, 2026, 'ATTENDANCE', 'A', pi.id, FALSE
FROM penalty_item pi
WHERE pi.year = 2026 AND pi.item_name = '지각' AND pi.is_deleted = FALSE
  AND NOT EXISTS (
    SELECT 1 FROM penalty_rule pr
    WHERE pr.academy_id = pi.academy_id AND pr.year = 2026
      AND pr.trigger_type = 'ATTENDANCE' AND pr.trigger_condition = 'A'
      AND pr.penalty_item_id = pi.id AND pr.is_deleted = FALSE);

INSERT INTO penalty_rule (academy_id, year, trigger_type, trigger_condition, penalty_item_id, active)
SELECT pi.academy_id, 2026, 'ATTENDANCE', 'ABSENT', pi.id, FALSE
FROM penalty_item pi
WHERE pi.year = 2026 AND pi.item_name = '무단결석' AND pi.is_deleted = FALSE
  AND NOT EXISTS (
    SELECT 1 FROM penalty_rule pr
    WHERE pr.academy_id = pi.academy_id AND pr.year = 2026
      AND pr.trigger_type = 'ATTENDANCE' AND pr.trigger_condition = 'ABSENT'
      AND pr.penalty_item_id = pi.id AND pr.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 4. 보호자 + 학생 연결
--
-- ★ is_approver 는 학생당 한 명뿐이다(I-12 "학부모 최대 1인"). 연락처는 여러 건이
--   될 수 있어서 부분 유니크로 묶여 있다 — 여기서는 1:1로만 넣는다.
-- ─────────────────────────────────────────────────────────────
-- ★ 학번(2026-0014)은 지점마다 다시 매겨져서 전 지점에 걸치면 겹친다.
--   phone 이 유니크라 학번으로 만들면 두 번째 지점에서 터진다 — student_id 로 만든다.
--
-- ★ 형식은 010-XXXX-XXXX 여야 한다. 자릿수가 어긋나면 마스킹이 국번을 잘못 잘라
--   화면에 이상한 번호가 뜬다(Masking 은 숫자만 뽑아 국번을 재판별한다).
INSERT INTO parent_guardian (name, phone, gender)
SELECT '보호자_' || e.student_no,
       '010-2' || LPAD(e.student_id::text, 3, '0') || '-' || LPAD(e.student_id::text, 4, '0'),
       'F'
FROM student_enrollment e
WHERE e.year = 2026 AND e.is_current = TRUE AND e.grade <> 'STAFF' AND e.is_deleted = FALSE
  AND NOT EXISTS (
    SELECT 1 FROM parent_guardian g
    WHERE g.phone = '010-2' || LPAD(e.student_id::text, 3, '0') || '-' || LPAD(e.student_id::text, 4, '0'));

INSERT INTO student_guardian_link (student_id, guardian_id, relation_order, is_approver)
SELECT e.student_id, g.id, 1, TRUE
FROM student_enrollment e
JOIN parent_guardian g ON g.phone = '010-2' || LPAD(e.student_id::text, 3, '0') || '-' || LPAD(e.student_id::text, 4, '0')
WHERE e.year = 2026 AND e.is_current = TRUE AND e.grade <> 'STAFF' AND e.is_deleted = FALSE
  AND NOT EXISTS (
    SELECT 1 FROM student_guardian_link l
    WHERE l.student_id = e.student_id AND l.guardian_id = g.id);

-- ─────────────────────────────────────────────────────────────
-- 5. 출결 태깅 로그 — 오늘 포함 최근 5일
--
-- ★ 이게 없으면 출결 화면이 전원 ABSENT 로 뜨고 순공시간이 0이다.
--
-- ★ 날짜가 CURRENT_DATE 기준이라 **넣은 날에 고정된다.** 다음 날 열면 그날이 빈다 —
--   날짜를 늘리는 건 문제를 미루는 것뿐이라, 5일치를 넣고 **매일 아침 seed-local.sh 를
--   한 번 돌리는 것**을 절차로 둔다. 멱등이라 여러 번 돌려도 안전하다.
--
-- ★ created_by 는 시스템 계정(0)이다 — 사람이 찍은 게 아니라 시드다.
--
-- ★ event_type 은 enum 이름이 아니라 DSA 코드 한 글자다(varchar(1)):
--   S 등원 · T 하원 · A 지각 · D 외출 · N 사유외출 · C 조퇴 · R 복귀
--
-- 학번 끝자리로 갈라 넣는다: 0~5 정상등원 · 6~7 지각 · 8 외출후복귀 · 9 결석(태깅 없음)
--
-- ★ 오늘은 하원을 넣지 않는다. 학생이 아직 안 갔으니 그게 실제 상태다 —
--   "하원 전"과 "완결된 날"이 화면에서 어떻게 다른지가 확인 항목이다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO attendance_tagging_log
    (academy_id, enrollment_id, event_type, source, recorded_at, attendance_date, created_by)
SELECT e.academy_id, e.id, t.event_type, 'KIOSK_NFC',
       (d.day + t.at) AT TIME ZONE 'Asia/Seoul', d.day, 0
FROM student_enrollment e
CROSS JOIN generate_series(CURRENT_DATE - 4, CURRENT_DATE, INTERVAL '1 day') AS g(day)
CROSS JOIN LATERAL (SELECT g.day::date AS day) d
CROSS JOIN LATERAL (
    SELECT * FROM (VALUES
        -- 정상 등원 (끝자리 0~5)
        ('S', TIME '08:40', '012345'),
        ('T', TIME '22:00', '012345'),
        -- 지각 (끝자리 6~7)
        ('A', TIME '10:20', '67'),
        ('T', TIME '22:00', '67'),
        -- 외출 후 복귀 (끝자리 8)
        ('S', TIME '08:45', '8'),
        ('D', TIME '13:00', '8'),
        ('R', TIME '14:00', '8'),
        ('T', TIME '22:00', '8')
    ) AS v(event_type, at, digits)
    WHERE POSITION(RIGHT(e.student_no, 1) IN v.digits) > 0
      -- 오늘은 아직 하원 전이다
      AND NOT (v.event_type = 'T' AND d.day = CURRENT_DATE)
) t
WHERE e.year = 2026 AND e.is_current = TRUE AND e.grade <> 'STAFF' AND e.is_deleted = FALSE
  -- ★ 이벤트 단위로 본다. (학생, 날짜)로만 보면 **일부만 들어간 날이 영영 안 채워진다** —
  --   실제로 하원 없이 등원만 있던 날이 그대로 남아 checkOutAt 이 계속 null 이었다
  AND NOT EXISTS (
    SELECT 1 FROM attendance_tagging_log l
    WHERE l.enrollment_id = e.id AND l.attendance_date = d.day
      AND l.event_type = t.event_type);

-- ─────────────────────────────────────────────────────────────
-- 6. 출결 확정 (attendance_daily_status) — 과거일만
--
-- ★ 오늘은 넣지 않는다. 확정 배치가 새벽 2시에 어제를 처리하므로 오늘이 없는 게
--   실제 상태다.
--
-- ★ 이게 없으면 화면이 비는 게 아니라 **틀린 값을 자신 있게 보여준다.**
--   unexcusedLate = late && !excused(confirmed) 라서, 확정이 없으면
--   사유 승인된 지각까지 전부 '무단지각'으로 나간다.
--   excused 도 확정에서만 나오므로 '사유 승인' 통계·필터·배지가 전부 0으로 굳는다.
--   study-time/recalculate 도 확정분만 다시 계산해서 영원히 {"updated":0} 이다.
--
-- ★ 지각 중 일부를 excused = TRUE 로 넣는다(끝자리 6).
--   상태(지각)와 사유(승인)가 직교하는 축이라는 걸 화면에서 확인하려면
--   "지각인데 무단이 아닌" 행이 있어야 한다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO attendance_daily_status
    (academy_id, enrollment_id, attendance_date, final_status, is_excused,
     manually_modified, created_by)
SELECT e.academy_id, e.id, d.day,
       CASE
           WHEN RIGHT(e.student_no, 1) = '9' THEN 'ABSENT'
           WHEN RIGHT(e.student_no, 1) IN ('6', '7') THEN 'LATE'
           ELSE 'PRESENT'
       END,
       -- 끝자리 6 = 사유 승인된 지각. 7 은 무단지각으로 남긴다
       RIGHT(e.student_no, 1) = '6',
       FALSE, 0
FROM student_enrollment e
CROSS JOIN generate_series(CURRENT_DATE - 4, CURRENT_DATE - 1, INTERVAL '1 day') AS g(day)
CROSS JOIN LATERAL (SELECT g.day::date AS day) d
WHERE e.year = 2026 AND e.is_current = TRUE AND e.grade <> 'STAFF' AND e.is_deleted = FALSE
  AND NOT EXISTS (
    SELECT 1 FROM attendance_daily_status s
    WHERE s.enrollment_id = e.id AND s.attendance_date = d.day);

-- ─────────────────────────────────────────────────────────────
-- 7. 승인 대기 사유신청
--
-- ★ PENDING 건이 없으면 승인/반려 버튼 → API → 목록 갱신 경로를 한 번도 밟을 수 없다.
--   대리승인이 열렸는지는 DB 로 확인되지만 화면 경로는 확인 항목이 따로다.
--
-- 지점별로 2건씩 — 학부모 대기 1건, 시간이 지나 에스컬레이션 후보가 된 1건.
--
-- ★ 대기 건은 학생당 하나뿐이다(uq_approval_request_pending). 두 케이스를 같은
--   학생에 넣을 수 없어 학번 끝자리로 나눈다 — 1은 방금, 2는 타임아웃 경과.
-- ─────────────────────────────────────────────────────────────
INSERT INTO approval_request
    (academy_id, approval_item_id, enrollment_id, status, requested_at,
     primary_approver, timeout_minutes, escalation_at, created_by)
SELECT e.academy_id, ai.id, e.id, 'PENDING',
       now() - t.ago, 'PARENT', ai.timeout_minutes,
       now() - t.ago + (ai.timeout_minutes || ' minutes')::interval, 0
FROM student_enrollment e
JOIN approval_item ai ON ai.academy_id = e.academy_id AND ai.year = e.year
                     AND ai.request_type = 'ABSENCE_REASON' AND ai.is_deleted = FALSE
CROSS JOIN LATERAL (VALUES
    -- 끝자리 1: 방금 신청 — 학부모 대기
    -- 끝자리 2: 타임아웃이 지났다 — 에스컬레이션 후보로 뜬다
    (CASE WHEN RIGHT(e.student_no, 1) = '1'
          THEN INTERVAL '5 minutes' ELSE INTERVAL '3 hours' END)
) AS t(ago)
WHERE e.year = 2026 AND e.is_current = TRUE AND e.grade <> 'STAFF' AND e.is_deleted = FALSE
  AND RIGHT(e.student_no, 1) IN ('1', '2')
  AND NOT EXISTS (
    SELECT 1 FROM approval_request r
    WHERE r.enrollment_id = e.id AND r.approval_item_id = ai.id AND r.status = 'PENDING');

INSERT INTO absence_reason
    (academy_id, enrollment_id, attendance_date, reason_type, reason_text,
     submitted_at, approval_request_id, created_by)
SELECT r.academy_id, r.enrollment_id, CURRENT_DATE,
       CASE WHEN r.escalation_at < now() THEN 'LATE' ELSE 'ABSENCE' END,
       CASE WHEN r.escalation_at < now() THEN '병원 진료로 지각' ELSE '가족 행사' END,
       r.requested_at, r.id, 0
FROM approval_request r
JOIN approval_item ai ON ai.id = r.approval_item_id AND ai.request_type = 'ABSENCE_REASON'
WHERE r.status = 'PENDING'
  AND NOT EXISTS (
    SELECT 1 FROM absence_reason a WHERE a.approval_request_id = r.id);

COMMIT;
