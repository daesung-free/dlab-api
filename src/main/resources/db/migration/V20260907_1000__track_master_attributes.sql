-- V20260907_1000: 계열 마스터에 코드·비고·사용여부 + 유니크 완화
--
-- 계열은 지금 (id, name)뿐이고 API 도 GET·POST 만 열려 있다.
-- 그래서 **한번 만들면 못 지운다** — 실제로 프론트가 확인용으로 넣은 행 하나를
-- 화면에서 지울 수 없어 DB 에서 직접 지웠다. 운영에서 오타로 하나 만들면 그대로 남는다.
--
-- ★ 왜 삭제보다 active 가 먼저인가
--   다른 마스터와 같은 이유다. 지우면 그 계열이었던 과거 데이터의 근거가 끊긴다.
--   active = FALSE 는 "새로 고를 수 없다"이고 기존 데이터는 남는다.
--
-- ★ name 의 UNIQUE 를 부분 인덱스로 바꾼다 — 이게 핵심이다.
--   지금은 컬럼 레벨 UNIQUE 라 **soft delete 된 행까지 이름을 붙잡고 있다.**
--   오타로 만든 '자연계여'를 지운 뒤 '자연계열'을 만드는 건 되지만,
--   '자연계열'을 잘못 지웠다가 다시 만들면 제약 위반으로 막힌다. 화면에는 그 계열이
--   안 보이니 "없는 걸 못 만든다"는 상태가 된다. room_master 에서 같은 걸 겪었다.

ALTER TABLE track_master ADD COLUMN code   VARCHAR(30);
ALTER TABLE track_master ADD COLUMN memo   VARCHAR(200);
ALTER TABLE track_master ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- 컬럼 레벨 UNIQUE 는 제약 이름이 자동 생성된다(track_master_name_key).
ALTER TABLE track_master DROP CONSTRAINT IF EXISTS track_master_name_key;

CREATE UNIQUE INDEX uq_track_master_name
    ON track_master (name) WHERE is_deleted = FALSE;

-- ★ 코드 유니크에 academy_id·year 축이 없다 — 계열은 전 지점 공통이라 그 축이 없다.
--   NULL 은 제약을 안 받는다(선택 입력이라 대부분 비어 있고, 포함시키면 코드 없는
--   계열을 하나밖에 못 만든다).
CREATE UNIQUE INDEX uq_track_master_code
    ON track_master (code) WHERE code IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN track_master.code IS '선택. 이름이 바뀌어도 유지되는 키';
COMMENT ON COLUMN track_master.active IS 'FALSE = 새로 고를 수 없음. 기존 데이터는 남는다';
