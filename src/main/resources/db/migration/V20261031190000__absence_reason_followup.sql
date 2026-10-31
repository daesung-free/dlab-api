-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다.

-- 사유 신청 후속 처리 — ★ 증빙 확인 · ★ 미진행 (디랩 1029 4-3)
--
-- ★★ 왜 생겼는가 — ★ 승인에 벌점이 붙게 되면서다.
--
--    ⚠️ 10/21 문서 3-3 은 *「외출 신청 후 안 나가면 ★ 취소할 벌점이 없다 —
--    개발할 것 없음」* 으로 정리하고 디랩에 그렇게 회신했다. ★ 승인 시 부여로
--    바뀌면서(1029 4-1) ★ 그 결론이 뒤집혔다 — ★ 안 나간 경우의 정리가 필요하다.
--
-- ── ① 증빙 확인 (#36, 0점 항목) ────────────────────────────────
--
-- 시트 원문: *「사유외출 및 조퇴 시 증빙영수증 제출할 경우 ★ 직원 확인 체크 시
--            기존 부여된 벌점 자동 삭제」*
--
-- ★ 직원이 체크하면 ★ 그 신청으로 붙은 자동 벌점을 거두고 0점 항목을 기록으로 남긴다.
--
-- ⚠️ 시트는 「사유외출 및 조퇴」만 적었지만 ★ 화면에서 막지 않는다 — 사유 결석·지각에
--    열지는 ★ 규칙 데이터로 정한다(1029 4-3 지침).
--
-- ── ② 미진행 (#10, 0점 항목) ───────────────────────────────────
--
-- ★ 승인됐는데 ★ 실제로 하지 않은 경우.
--
-- ⚠️ 정리 방식이 미확정이다(9-7 회신 대기):
--    ㉮ -1 을 ★ 취소하고 0점 기록만 남긴다
--    ㉯ -1 을 ★ 두고 0점 기록을 더한다
--
-- ★★ 그런데 ★ 그 차이는 ★ penalty_rule.supersedes_rule_id 로 흡수된다 —
--    「미진행」 규칙에 ★ 대체 대상을 지정하면 ㉮, 비우면 ㉯ 다.
--    ★ 즉 ★ 회신이 와도 코드를 고치지 않고 ★ 행만 고친다.
--
-- ★ 둘 다 ★ 직원이 누르는 버튼 하나로 끝나야 한다(1029 4-3) —
--   ⚠️ 항목을 손으로 찾아 넣게 하면 ★ 빠진다.

ALTER TABLE absence_reason
    ADD COLUMN IF NOT EXISTS evidence_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS evidence_confirmed_by BIGINT,
    ADD COLUMN IF NOT EXISTS not_executed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS not_executed_by BIGINT;

ALTER TABLE absence_reason
    ADD CONSTRAINT fk_absence_reason_evidence_by
        FOREIGN KEY (evidence_confirmed_by) REFERENCES account (id),
    ADD CONSTRAINT fk_absence_reason_not_executed_by
        FOREIGN KEY (not_executed_by) REFERENCES account (id);

COMMENT ON COLUMN absence_reason.evidence_confirmed_at IS
    '직원이 증빙(병원영수증 등)을 확인한 시각. 확인하면 그 신청으로 붙은 자동 벌점을 '
    '거두고 0점 항목을 기록으로 남긴다(1029 4-3 #36).';

COMMENT ON COLUMN absence_reason.not_executed_at IS
    '승인됐는데 실제로 하지 않은 것을 직원이 표시한 시각(1029 4-3 #10). '
    '벌점을 거둘지는 penalty_rule.supersedes_rule_id 로 정한다 — 9-7 회신과 무관하게 '
    '데이터로 흡수된다.';
