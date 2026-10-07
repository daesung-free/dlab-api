-- 회원 탈퇴 (App Store 5.1.1(v) · Google Play 필수 요건).
--
-- ★ 앱이 DELETE /app/me 를 부르는데 서버에 그 경로가 없었다. 가입이 있는 앱은
--   「앱 안에서 계정 삭제를 요청하는 경로」가 있어야 하고, 없으면 심사에서 반려된다.
--
-- ★ status 만으로는 구분이 안 된다. AccountStatus.WITHDRAWN 은 이미 쓰이고 있는데
--   그건 「퇴원·제적·수료로 학원이 앱 접근을 끊은 것」이다. 본인이 탈퇴한 것과는
--   다른 사건이고 후속이 다르다 — 퇴원생은 재등록하면 다시 열어 주지만,
--   본인 탈퇴는 본인이 다시 가입해야 한다.

ALTER TABLE account
    -- 값이 있으면 「본인이 탈퇴한 것」이다. status 는 둘 다 WITHDRAWN 이 된다
    ADD COLUMN withdrawn_at      TIMESTAMPTZ,
    ADD COLUMN withdrawal_reason VARCHAR(200);

COMMENT ON COLUMN account.withdrawn_at IS
    '본인 탈퇴 시각. 퇴원 처리(deactivate)와 구분하는 유일한 값이다';
COMMENT ON COLUMN account.withdrawal_reason IS
    '탈퇴 사유(선택 입력). 개선 근거로만 쓰고 재가입을 막는 데 쓰지 않는다';
