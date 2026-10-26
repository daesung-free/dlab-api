-- 카카오 승인본 문구를 서버에 넣는다 (2026-10-23 심사 승인분).
--
-- ★★ 알리고에만 등록하고 서버 DB 에는 넣지 않은 상태였다. 그래서 둘이 막혀 있었다.
--
--   ① 심사 결과 입력이 거부된다 — submitForReview 가 제목·본문이 비면 400 이다.
--      실제로 13건 전부 "문구가 비어 있어 심사를 제출할 수 없습니다" 가 났다.
--   ② ★ 코드를 넣었다 해도 **빈 메시지가 나간다** — 서버가 보내는 문장은 이 body_template 이다.
--
-- ⚠️⚠️ **문구를 손으로 고치지 말 것.** 카카오는 승인된 템플릿 내용과 대조하므로
--   띄어쓰기 한 칸만 달라도 발송이 거절된다. 아래 문장은 알리고 API 로 **승인본을 그대로
--   받아** 변수명만 우리 키로 바꾼 것이다(#{학생명} -> {studentName}).
--   고쳐야 하면 **알리고에서 수정 -> 재심사 -> 그 결과를 여기 반영** 순서다.
--
-- ★ 제목(title_template)은 알림톡 화면에 뜨지 않는다. 알리고 send API 의 subject 가
--   필수라서 넣는 값이고, 우리 문구 검증도 제목을 요구한다.
--
-- ★ 수동 발송 2종(급식 마감·등록비 미납)은 변수명이 한글이다 — 직원이 화면에서 채운다.
--   그대로 둔다.
--
-- ⚠️ 넣지 않는 것
--     TUITION_REGISTRATION_NOTICE  카카오 반려 -> 문구 고쳐 재심사 중(UM_8514).
--                                  승인되면 student_specific 도 TRUE 로 되돌려야 한다
--                                  (학생명이 들어갔다).
--     대기자 등록 안내(UM_8513)      승인됐으나 대응 이벤트가 없다(대상 선택 축 미구현).

-- 등원 안내 (UM_8230)
UPDATE notification_template
   SET title_template = '등원 안내',
       body_template = '[DLab] {studentName} 학생이 {taggedAt}에 등원했습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'ATTENDANCE_CHECK_IN';

-- 하원 안내 (UM_8231)
UPDATE notification_template
   SET title_template = '하원 안내',
       body_template = '[DLab] {studentName} 학생이 {taggedAt}에 하원했습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'ATTENDANCE_CHECK_OUT';

-- 조퇴 안내 (UM_8232)
UPDATE notification_template
   SET title_template = '조퇴 안내',
       body_template = '[DLab] {studentName} 학생이 {taggedAt}에 조퇴하였습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'ATTENDANCE_EARLY_LEAVE';

-- 지각 안내 (UM_8233)
UPDATE notification_template
   SET title_template = '지각 안내',
       body_template = '[DLab] {studentName} 학생이 {taggedAt}에 지각 등원하였습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'ATTENDANCE_LATE';

-- 외출 안내 (UM_8234)
UPDATE notification_template
   SET title_template = '외출 안내',
       body_template = '[DLab] {studentName} 학생이 {taggedAt}에 외출하였습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'ATTENDANCE_OUTING';

-- 외출 복귀 안내 (UM_8235)
UPDATE notification_template
   SET title_template = '외출 복귀 안내',
       body_template = '[DLab] {studentName} 학생이 {taggedAt}에 외출 복귀하였습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'ATTENDANCE_RETURN';

-- 결석 안내 (UM_8236)
UPDATE notification_template
   SET title_template = '결석 안내',
       body_template = '[DLab] {studentName} 학생이 {attendanceDate} 등원하지 않았습니다.
확인이 필요하시면 학원으로 연락해 주십시오.',
       content_confirmed = TRUE
 WHERE event_code = 'MISSING_ATTENDANCE';

-- 급식 신청 마감 안내 (UM_8237)
UPDATE notification_template
   SET title_template = '급식 신청 마감 안내',
       body_template = '[DLab] {월} 급식 신청이 {날짜}에 마감됩니다.',
       content_confirmed = TRUE
 WHERE event_code = 'MEAL_APPLY_DEADLINE';

-- 등록비 미납 안내 (UM_8239)
UPDATE notification_template
   SET title_template = '등록비 미납 안내',
       body_template = '[DLab] {studentName} 학생의 {월} 등록비가 미납 상태입니다.',
       content_confirmed = TRUE
 WHERE event_code = 'TUITION_UNPAID_NOTICE';

-- 승인 요청 접수 (UM_8240)
UPDATE notification_template
   SET title_template = '승인 요청 접수',
       body_template = '[DLab] {studentName} 학생이 {requestType}을 신청하였습니다.
{requestedAt}부터 {timeoutMinutes}분 이내에 승인해 주십시오.',
       content_confirmed = TRUE
 WHERE event_code = 'APPROVAL_REQUEST_CREATED';

-- 승인 권한 이관 (UM_8241)
UPDATE notification_template
   SET title_template = '승인 권한 이관',
       body_template = '[DLab] {studentName} 학생의 신청에 대해 {requestedAt}부터 {timeoutMinutes}분 이내에 응답이 없어 승인 권한이 교직원에게 이관되었습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'APPROVAL_HANDED_OVER';

-- 교직원 승인 안내 (UM_8242)
UPDATE notification_template
   SET title_template = '교직원 승인 안내',
       body_template = '[DLab] {studentName} 학생의 {requestType} 신청을 {resolvedAt}에 교직원이 승인하였습니다.
응답 기한({timeoutMinutes}분)이 경과하여 교직원이 처리하였습니다.',
       content_confirmed = TRUE
 WHERE event_code = 'APPROVAL_APPROVED_AFTER_TIMEOUT';

-- 교직원 승인 안내 (UM_8243)
UPDATE notification_template
   SET title_template = '교직원 승인 안내',
       body_template = '[DLab] {studentName} 학생의 {requestType} 신청을 {resolvedAt}에 교직원이 승인하였습니다.
응답 기한({timeoutMinutes}분)이 경과하기 전에 교직원이 우선 처리하였습니다.',
       content_confirmed = TRUE,
       required_variables = 'studentName,requestType,resolvedAt,timeoutMinutes'
 WHERE event_code = 'APPROVAL_APPROVED_BEFORE_TIMEOUT';
