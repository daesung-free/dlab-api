-- 현금영수증 취소 승인번호
--
-- ★ 취소하면 발급과 다른 승인번호가 온다. 취소도 국세청에 "-" 매출로 등록되는 별개의
--   건이라서다 — 발급 승인번호를 덮어쓰면 무엇을 신고했는지 대조할 수 없다.
ALTER TABLE cash_receipt
    ADD COLUMN cancel_receipt_no VARCHAR(20);

COMMENT ON COLUMN cash_receipt.cancel_receipt_no IS '취소 승인번호. 발급 승인번호(receipt_no)와 다른 값이다';
