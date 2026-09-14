-- 학부모가 타임아웃을 넘겨 승인한 경우를 따로 남긴다.
--
-- ★ 지금까지는 승인자가 학부모면 시각을 보지 않고 전부 PARENT_IN_TIME 이었다.
--   그래서 열 시간 뒤에 승인한 건도 "시간 내 승인"으로 기록된다 — 담임 쪽은 전후를
--   가르는데(STAFF_BEFORE_TIMEOUT / STAFF_AFTER_TIMEOUT) 학부모만 한 칸이었다.
--
-- ★ 이 값이 필요한 이유는 통계다. "학부모 응답이 늦어 담임이 처리한 비율"을 보려면
--   늦게라도 학부모가 처리한 건이 정상 응답과 섞이면 안 된다. 타임아웃(10분)이 실제
--   운영에 맞는지도 이 숫자로 판단한다.
--
-- 문구는 PARENT_IN_TIME 과 같다 — 학부모가 승인했다는 사실은 같고, 늦었다는 것을
-- 굳이 학부모에게 알릴 이유가 없다. 나누는 것은 기록이지 안내가 아니다.
ALTER TABLE approval_request DROP CONSTRAINT IF EXISTS approval_request_resolution_case_check;

ALTER TABLE approval_request
    ADD CONSTRAINT approval_request_resolution_case_check
    CHECK (resolution_case IN (
        'PARENT_IN_TIME',
        'PARENT_AFTER_TIMEOUT',
        'STAFF_AFTER_TIMEOUT',
        'STAFF_BEFORE_TIMEOUT',
        'STAFF_PRIMARY',
        'ADMIN_PROXY'
    ));

-- ⚠️ 기존 행은 그대로 둔다. PARENT_IN_TIME 으로 기록된 과거 건 중 무엇이 늦은
--    승인이었는지 되짚을 수는 있으나(resolved_at vs escalation_at), 그때의 판정을
--    지금 기준으로 덮어쓰면 "그 시점에 무엇으로 기록됐는가" 가 사라진다.

COMMENT ON COLUMN approval_request.resolution_case IS
    'PARENT_IN_TIME(정상) / PARENT_AFTER_TIMEOUT(학부모가 늦게 승인) / '
    'STAFF_AFTER_TIMEOUT(무응답 후 담임) / STAFF_BEFORE_TIMEOUT(시간 남았는데 담임) / '
    'STAFF_PRIMARY(우선 승인자가 직원) / ADMIN_PROXY(관리자 대리)';
