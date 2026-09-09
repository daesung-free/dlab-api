-- 알림톡인데 심사 대상이 아닌 것으로 들어간 템플릿 정정
--
-- ⚠️ 파일명이 14자리인 이유 — review_status 를 만드는 V20260806100000 이 14자리라,
--    밑줄 방식(V20260907_1600)으로 지으면 20260907.1600 으로 파싱돼 그보다 먼저 실행된다.
--    빈 DB 에서 "column review_status does not exist" 로 깨진다.
--
-- ■ 어쩌다 이렇게 됐나
--
-- V20260806100000 이 "알림톡은 DRAFT" 로 한 번 정리했는데, 그보다 나중인
-- V20260811140000 이 알림톡 템플릿 2건(APPROVAL_REMINDER · APPROVAL_HANDED_OVER)을
-- 새로 넣으면서 review_status 를 안 줬다. 컬럼 기본값이 NOT_REQUIRED 라 그대로 들어갔다.
--
-- ■ 왜 문제인가
--
-- NOT_REQUIRED 는 canSend()를 통과한다(FCM 은 심사가 없어서 그렇게 뒀다).
-- 그래서 문구만 확정하면 sendable=true 가 되는데, 카카오는 사전 승인 문안만 받으므로
-- 실제로는 거절된다 — 화면은 "나감"인데 안 나가고, 원인이 우리 쪽에 안 남는다.
--
-- 판정 쪽도 함께 고쳤다(NotificationTemplate.isSendable 이 채널을 본다).
-- 데이터만 고치면 다음에 또 같은 방식으로 들어온다.
UPDATE notification_template
SET review_status = 'DRAFT'
WHERE channel = 'KAKAO_ALIMTALK'
  AND review_status = 'NOT_REQUIRED';
