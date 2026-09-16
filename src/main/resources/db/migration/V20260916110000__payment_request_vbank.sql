-- 가상계좌 발급 정보.
--
-- ★ 바이링크와 같은 표(payment_request)에 둔다. 흐름이 같기 때문이다 —
--   "요청했지만 아직 안 낸" 상태가 있고, 완료는 Webhook 이 확정한다.
--   표를 나누면 미납 조회·영수증·취소를 두 벌로 짜게 된다.
--
-- ★ 채널만 다르다. 바이링크는 결제 URL 이 나오고, 가상계좌는 계좌번호가 나온다.
ALTER TABLE pg_site DROP CONSTRAINT IF EXISTS pg_site_channel_check;
ALTER TABLE pg_site
    ADD CONSTRAINT pg_site_channel_check
    CHECK (channel IN ('BUYLINK', 'TERMINAL', 'VBANK'));

ALTER TABLE payment_request
    -- 발급된 가상계좌. 학부모에게 알려줄 값이다
    ADD COLUMN vbank_account   VARCHAR(30),
    ADD COLUMN vbank_bank_name VARCHAR(30),
    ADD COLUMN vbank_bank_code VARCHAR(10),
    -- 예금주. KCP 가 가맹점명으로 내려준다
    ADD COLUMN vbank_depositor VARCHAR(30),
    -- 실제 입금자. ★ 예금주와 다를 수 있다(할머니가 대신 내는 경우) — 대조에 쓰지 말 것
    ADD COLUMN vbank_remitter  VARCHAR(30);

-- 가상계좌는 결제수단이 VCNT 다. 기존 CHECK 에 없어 발급 자체가 막힌다.
ALTER TABLE payment_request DROP CONSTRAINT IF EXISTS payment_request_pay_method_check;
ALTER TABLE payment_request
    ADD CONSTRAINT payment_request_pay_method_check
    CHECK (pay_method IN ('CARD', 'BANK', 'MOBX', 'VCNT'));

COMMENT ON COLUMN payment_request.vbank_remitter IS
    '실제 입금자명. 예금주·학생명과 다를 수 있다 — 본인 확인 수단으로 쓰지 말 것';
COMMENT ON COLUMN payment_request.expire_at IS
    '바이링크는 링크 만료, 가상계좌는 입금 기한. 지나면 그 계좌로 입금할 수 없다';
