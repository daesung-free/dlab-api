-- V20260902100000: 관리자 대리승인 (F-4.1-6)
--
-- ★ 파일명이 14자리인 이유 (CLAUDE.md §2)
--   V20260902_1000 은 20260902.1000 으로 파싱돼 14자리 V20260811140000 보다 **작다**.
--   그러면 이 파일이 8월 것보다 먼저 돌고, 8월 마이그레이션이 CHECK 제약을
--   ADMIN_PROXY 없는 옛 목록으로 덮어쓴다 — 실제로 그렇게 깨졌다.
--   14자리 파일을 건드리는 마이그레이션은 14자리로 지을 것.
--
-- 지금까지 승인이 학부모(연결된 보호자)와 담임(그 요청의 에스컬레이션 대상 본인)만
-- 통과했다. 그래서 관리자 웹의 사유신청 승인 화면이 조회 전용이 됐다 — 프론트에서
-- 실제로 막혔다.
--
-- ★ 요구사항에 근거가 있다.
--   F-4.1-6: "앱에서 사유 즉시 제출 → 실시간 확인/수정·승인. 관리자 직접 등록도 병행"
--   메뉴매핑: "DSA는 관리자 직접 처리 구조만 존재"
--   원래 관리자가 처리하던 것을 승인 라우팅으로 확장한 것이지 관리자를 뺀 것이 아니다.
--
-- ⚠️ 어느 role 까지 열지는 I-12(승인 주체 매트릭스, 최우선·미해결) 대기다.
--    우선 SUPER_ADMIN·BRANCH_ADMIN 으로 열고 매트릭스 수령 시 조정한다.

-- ─────────────────────────────────────────────────────────────
-- 1. 처리자 유형에 ADMIN 추가
--
-- ★ TEACHER 로 기록하면 안 된다. 담임이 아닌 사람이 담임으로 남고,
--   "누가 승인했나"에 답할 수 없게 된다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE approval_request DROP CONSTRAINT IF EXISTS approval_request_resolver_type_check;

ALTER TABLE approval_request
    ADD CONSTRAINT approval_request_resolver_type_check
    CHECK (resolver_type IN ('PARENT', 'TEACHER', 'ADMIN'));

-- ─────────────────────────────────────────────────────────────
-- 2. 처리 케이스에 ADMIN_PROXY 추가
--
-- ★ 기존 넷과 합치면 안 된다. 전부 학부모에게 나가는 안내 문구가 다르고,
--   관리자 대리는 그중 어느 것도 아니다:
--     PARENT_IN_TIME       정상
--     STAFF_AFTER_TIMEOUT  "시간이 지나 담임이 승인했습니다"
--     STAFF_BEFORE_TIMEOUT "시간이 남았지만 담임이 먼저 승인했습니다"
--     STAFF_PRIMARY        애초에 직원이 승인자
--   관리자 대리는 담임도 아니고 우선 승인자도 아니다 — 데스크에서 대신 처리한 것이다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE approval_request DROP CONSTRAINT IF EXISTS approval_request_resolution_case_check;

ALTER TABLE approval_request
    ADD CONSTRAINT approval_request_resolution_case_check
    CHECK (resolution_case IN (
        'PARENT_IN_TIME',
        'STAFF_AFTER_TIMEOUT',
        'STAFF_BEFORE_TIMEOUT',
        'STAFF_PRIMARY',
        'ADMIN_PROXY'
    ));

COMMENT ON COLUMN approval_request.resolution_case IS
    'PARENT_IN_TIME(정상) / STAFF_AFTER_TIMEOUT(무응답 후 담임) / STAFF_BEFORE_TIMEOUT(시간 남았는데 담임) / '
    'STAFF_PRIMARY(우선 승인자가 직원) / ADMIN_PROXY(관리자 대리). 문구가 전부 달라야 한다';

-- ─────────────────────────────────────────────────────────────
-- 3. 알림 이벤트
--
-- ⚠️ 문구는 여전히 미확정이다(I-4). 매핑만 만들어두면 문구가 오는 대로 템플릿을
--    채우면 되고, 그전까지는 발송이 SKIPPED 로 남는다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO notification_template (event_code, channel, required_variables) VALUES
    -- 관리자가 데스크에서 대신 처리함. 학부모에겐 통지 성격이라 푸시로 충분하다
    ('APPROVAL_APPROVED_BY_ADMIN', 'FCM_PUSH', 'studentName,resolvedAt');
