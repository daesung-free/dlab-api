-- 승인 알림톡: 승인항목(requestType) 변수 추가 + 재요청톡 비활성 (디랩 1016 문구 확정분)
--
-- ★★ 카카오 템플릿은 등록한 뒤 변수를 더하면 재심사다. 문구를 올리기 전인 지금 넣는다 —
--    나중이면 승인 대기가 며칠 늘어난다.
--
-- ★ 파일명을 14자리로 지은 이유: 밑줄 방식(V20261016_1000)은 Flyway 가 `20261016.1000`
--   으로 파싱해 **14자리 파일보다 먼저** 실행한다. 아래 UPDATE 가 건드리는
--   APPROVAL_REMINDER 행은 V20260811140000(14자리)이 넣으므로, 밑줄로 지으면
--   **아직 없는 행을 UPDATE 해서 0건을 먹고 조용히 넘어간다.**

-- ─────────────────────────────────────────────────────────────
-- 1. 승인항목 변수 — 문구에 [승인항목]이 들어가는 세 건만
--
-- 디랩 문구 11번(접수) · 13번(교직원 승인, 시간 경과 후) · 14번(교직원 승인, 시간 내).
-- ⚠️ **코드가 먼저다.** required_variables 에만 넣고 ApprovalService 가 값을 채우지 않으면
--    NotificationService 가 markSkipped("변수 누락")으로 거른다 — 발송 없이 로그만 남고
--    화면에는 오류도 뜨지 않는다. 같은 커밋에서 resolvedVariables()에 넣었다.
-- ─────────────────────────────────────────────────────────────
UPDATE notification_template
   SET required_variables = 'studentName,requestType,requestedAt,timeoutMinutes',
       updated_at         = now()
 WHERE event_code = 'APPROVAL_REQUEST_CREATED';

UPDATE notification_template
   SET required_variables = 'studentName,requestType,resolvedAt,timeoutMinutes',
       updated_at         = now()
 WHERE event_code = 'APPROVAL_APPROVED_AFTER_TIMEOUT';

UPDATE notification_template
   SET required_variables = 'studentName,requestType,resolvedAt',
       updated_at         = now()
 WHERE event_code = 'APPROVAL_APPROVED_BEFORE_TIMEOUT';

-- 이양톡(12번)·재요청톡에는 문구상 승인항목이 없어 required 에 넣지 않는다.
-- 코드는 값을 싣는다 — 변수가 더 있는 것은 무해하고, 문구에 넣게 되면 여기만 고치면 된다.

-- ─────────────────────────────────────────────────────────────
-- 2. 재요청톡 비활성 (디랩 1016: 「승인 무응답 재요청톡」 삭제)
--
-- ★ 이벤트와 스케줄러를 지우지 않는다. 지우면 「재요청 후 같은 대기시간을 한 번 더 준 뒤
--   이양」이라는 흐름이 깨져서, 학부모가 알림을 보고 들어올 틈 없이 바로 이양된다.
--   발송만 멈추면 되고, NotificationService 가 markSkipped("비활성 템플릿")으로 거른다.
-- ─────────────────────────────────────────────────────────────
UPDATE notification_template
   SET active     = FALSE,
       updated_at = now()
 WHERE event_code = 'APPROVAL_REMINDER';
