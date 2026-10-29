-- V1의 오래된 멱등 인덱스는 취소된 행도 유일성에 포함한다.
-- V4의 부분 인덱스와 함께 남아 있으면 자동 벌점을 취소 후 다시 부여할 때
-- soft-deleted 행과 충돌한다. 운영에서 확인된 구 인덱스만 제거한다.
DROP INDEX IF EXISTS uq_penalty_idempotency;
