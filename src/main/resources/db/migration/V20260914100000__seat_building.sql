-- V20260914100000: 「관」축 — 본관/별관을 구분해 좌석번호를 겹쳐 쓴다
--
-- ★ 왜 필요한가
--   동탄2관은 동탄 본관의 별관인데 A·B·C 구역과 좌석번호가 본관과 똑같다.
--   DSA 는 seat_cd 하나로만 좌석을 찾기 때문에 구분할 방법이 없어 별관을
--   1000번대로 돌려 운영해 왔다(1번 → 1001번). 클라이언트 요구는
--   **같은 번호를 쓰되 본관/별관이 구분되고, 별관이 더 생겨도 되는 것**이다.
--
-- ★ 그런데 그냥 UNIQUE 를 풀면 키오스크가 깨진다
--   키오스크는 setSeatChgProc 에서 area 없이 seat_cd 만 보낸다. 같은 번호가 둘이면
--   어느 자리인지 정할 수 없다 — DSA 가 1000번대로 우회하던 이유가 정확히 이것이다.
--
-- ★ 그래서 축을 둘로 나눈다
--   - 우리 DB·화면 : (관, 구역, 좌석번호) 로 구분한다. 화면에는 본래 번호가 뜬다
--   - 키오스크 응답 : kiosk_seat_cd / kiosk_area_cd 를 따로 들고 그 값을 내린다
--   즉 **계약은 그대로 두고 우리 쪽에 변환된 값을 미리 저장**한다.
--
-- ★★ 변환값을 계산해서 내리지 않고 컬럼으로 저장하는 이유
--   즉석에서 offset 을 더해 내리면 **본관에 이미 1001번이 있을 때 조용히 충돌**한다
--   (본관 1001번 과 별관 1번+1000 이 같은 값이 된다). 그러면 키오스크에서 두 자리가
--   한 자리로 합쳐져 보이는데, 우리 DB 는 멀쩡하므로 원인을 찾기가 매우 어렵다.
--   컬럼으로 두면 UNIQUE (academy_id, kiosk_seat_cd) 가 **등록 시점에** 막는다.
--   DB 가 보장하는 것과 코드가 지키기로 한 것은 다르다.
--
-- ★ 덤으로 UNIQUE 를 부분 인덱스로 바꾼다
--   기존 uq_seat_master / uq_study_area 는 soft delete 를 모른다. 좌석을 지웠다가
--   같은 번호로 다시 만들면 제약에 걸리는데 화면에는 그 좌석이 없으니 "없는 걸
--   못 만든다"가 된다. 지금까지는 서비스가 삭제분을 찾아 되살려서 가렸다.
--   room_master(V20260904_1300)·locker 와 같은 판단이다.


-- ==========================================================================
-- 1. 관(building)
-- ==========================================================================

CREATE TABLE building (
    id            BIGSERIAL    PRIMARY KEY,
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),
    code          VARCHAR(20)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    sort_order    SMALLINT     NOT NULL DEFAULT 0,
    -- 0 = 본관. 키오스크에 내릴 좌석번호를 이만큼 밀어 올린다
    seat_cd_offset INT         NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_building_offset CHECK (seat_cd_offset >= 0)
);

COMMENT ON TABLE building IS
    '관(본관/별관). 좌석번호가 겹치는 별관을 구분하는 축';
COMMENT ON COLUMN building.seat_cd_offset IS
    '키오스크에 내릴 때 좌석번호에 더할 값. 본관 0, 동탄2관 1000. '
    '생성 후 변경 불가 — 이미 저장된 kiosk_seat_cd 와 어긋난다';

CREATE UNIQUE INDEX uq_building_code
    ON building (academy_id, code) WHERE is_deleted = FALSE;


-- ==========================================================================
-- 2. 지점마다 본관을 하나씩 만든다
-- ==========================================================================
--
-- ★ 기존 구역·좌석은 전부 본관 소속이 된다. 별관은 지금 운영에서 1000번대로
--   따로 등록돼 있을 텐데, 그 행들을 여기서 옮기지 않는다 —
--   어느 것이 별관인지 번호만 보고 단정할 수 없다. 컷오버 체크리스트 항목이다.
--
-- created_by 0 은 시스템 계정이다(CLAUDE.md §7). NULL 로 두면
-- "마이그레이션이 만들었다"와 "그냥 빠뜨렸다"가 구분되지 않는다.

INSERT INTO building (academy_id, code, name, sort_order, seat_cd_offset, created_by)
SELECT a.id, 'MAIN', '본관', 0, 0, 0
FROM academy a;


-- ==========================================================================
-- 3. 구역에 관을 붙인다
-- ==========================================================================

ALTER TABLE study_area ADD COLUMN building_id   BIGINT REFERENCES building (id);
ALTER TABLE study_area ADD COLUMN kiosk_area_cd VARCHAR(50);

UPDATE study_area sa
   SET building_id   = b.id,
       kiosk_area_cd = sa.area_cd
  FROM building b
 WHERE b.academy_id = sa.academy_id
   AND b.code = 'MAIN';

ALTER TABLE study_area ALTER COLUMN building_id   SET NOT NULL;
ALTER TABLE study_area ALTER COLUMN kiosk_area_cd SET NOT NULL;

COMMENT ON COLUMN study_area.kiosk_area_cd IS
    '키오스크에 내리는 구역코드. 본관은 area_cd 와 같고 별관은 관 코드가 앞에 붙는다';

-- 지점 안에서 유일하던 것을 관 안에서 유일한 것으로 바꾼다
ALTER TABLE study_area DROP CONSTRAINT IF EXISTS uq_study_area;

CREATE UNIQUE INDEX uq_study_area_cd
    ON study_area (building_id, area_cd) WHERE is_deleted = FALSE;

-- ★ 이쪽이 키오스크 계약을 지키는 제약이다. 지점 안에서 유일해야 한다
CREATE UNIQUE INDEX uq_study_area_kiosk_cd
    ON study_area (academy_id, kiosk_area_cd) WHERE is_deleted = FALSE;

CREATE INDEX idx_study_area_building ON study_area (building_id) WHERE NOT is_deleted;


-- ==========================================================================
-- 4. 좌석
-- ==========================================================================
--
-- ★ seat_master 에는 building_id 를 두지 않는다.
--   구역을 타고 가면 알 수 있고, 두 벌 들면 구역을 옮겼을 때 한쪽만 바뀐다.
--   유니크는 (study_area_id, seat_cd) 로 충분하다 —
--   같은 관의 다른 구역에 같은 번호가 있으면 kiosk_seat_cd 가 같아져서
--   아래 uq_seat_master_kiosk_cd 에 걸린다.

ALTER TABLE seat_master ADD COLUMN kiosk_seat_cd VARCHAR(50);

UPDATE seat_master SET kiosk_seat_cd = seat_cd;

ALTER TABLE seat_master ALTER COLUMN kiosk_seat_cd SET NOT NULL;

COMMENT ON COLUMN seat_master.kiosk_seat_cd IS
    '키오스크에 내리는 좌석번호. 본관은 seat_cd 와 같고 별관은 관 offset 이 더해진다. '
    '화면에는 seat_cd 를 쓴다';

ALTER TABLE seat_master DROP CONSTRAINT IF EXISTS uq_seat_master;

CREATE UNIQUE INDEX uq_seat_master_cd
    ON seat_master (study_area_id, seat_cd) WHERE is_deleted = FALSE;

-- ★ 키오스크가 area 없이 seat_cd 만으로 좌석을 찾는다(setSeatChgProc).
--   이 제약이 깨지면 단말에서 두 자리가 한 자리로 보인다
CREATE UNIQUE INDEX uq_seat_master_kiosk_cd
    ON seat_master (academy_id, kiosk_seat_cd) WHERE is_deleted = FALSE;
