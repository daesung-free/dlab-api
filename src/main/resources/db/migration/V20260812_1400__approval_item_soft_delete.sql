-- V20260812_1400: 승인 정책 유니크를 부분 인덱스로 (F-4.11-5)
--
-- ★ 삭제한 정책이 계속 적용되고 있었다.
--   ApprovalService가 정책을 찾을 때 is_deleted를 안 봐서, soft delete한 행이
--   그대로 라우팅에 쓰였다 — 관리자는 지웠다고 생각하는데 신청이 계속 통한다.
--   조회 쪽을 고치면(deleted = false 추가) 이번엔 유니크 제약이 발목을 잡는다:
--   삭제된 행이 남아 있어 같은 유형을 다시 만들 수 없다.
--
--   그래서 제약을 부분 인덱스로 바꾼다. 살아 있는 행만 유일하면 된다.

ALTER TABLE approval_item DROP CONSTRAINT IF EXISTS uq_approval_item;

CREATE UNIQUE INDEX uq_approval_item
    ON approval_item (academy_id, year, request_type) WHERE is_deleted = FALSE;
