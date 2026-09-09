-- 방화벽 해제 시간 구간 (앱 시안 피드백 p4 · 5)
--
-- "해제 시간을 정확히 설정할 수 있는 기능으로 변경. 예) 15:00 ~ 17:00"
--
-- ■ 지금은 지속시간만 받는다
--
-- requested_minutes(1~300분)만 받고, 승인되는 순간부터 그만큼 열린다.
-- 그래서 "3시에 쓸 건데 미리 신청해두기"가 안 된다 — 승인이 1시에 나면 1시부터 열린다.
-- 같은 피드백에 "최소 3시간 전에 미리 해주세요" 안내를 넣어달라는 요청이 함께 온 것도
-- 미리 신청하는 흐름을 전제한 것이다.
--
-- ■ requested_minutes 를 없애지 않는다
--
-- 구간을 안 보내면 종전대로 동작한다. 이미 들어온 신청과 앱 구버전이 그대로 돌아야 한다.
-- 구간을 보내면 분 수는 거기서 계산해 함께 채운다 — 두 값이 어긋나면 어느 쪽이
-- 진실인지 판정할 수 없으므로 서버가 맞춰서 넣는다.
ALTER TABLE firewall_request
    ADD COLUMN requested_start_at TIMESTAMPTZ,
    ADD COLUMN requested_end_at   TIMESTAMPTZ;

ALTER TABLE firewall_request
    ADD CONSTRAINT ck_firewall_requested_window
        CHECK (
            (requested_start_at IS NULL AND requested_end_at IS NULL)
            OR (requested_start_at IS NOT NULL AND requested_end_at IS NOT NULL
                AND requested_end_at > requested_start_at)
        );

-- 예약 시작 배치가 "승인됐고 시작 시각이 된 것"을 훑는다
CREATE INDEX idx_firewall_requested_start
    ON firewall_request (requested_start_at)
    WHERE requested_start_at IS NOT NULL;

COMMENT ON COLUMN firewall_request.requested_start_at IS
    '학생이 지정한 해제 시작 시각. NULL 이면 승인 즉시 시작(종전 방식)';
