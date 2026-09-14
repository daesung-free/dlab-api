-- 구역에 종류를 둔다 — 독서실(STUDY)과 반 교실(CLASSROOM).
--
-- ★ 좌석은 지금까지 구역에만 속하고 반에는 속하지 않았다. 반 좌석표를 같은 테이블로
--   만들면(그게 낫다 — 좌석 생성·격자·배치도·사용중지가 전부 재사용된다) 목록이 섞이고,
--   무엇보다 키오스크가 구역 목록을 그대로 받아가므로 단말에 교실이 뜬다.
--
-- ★ 기본값을 STUDY 로 둔다. 지금 있는 구역은 전부 독서실이고, 값을 나중에 넣으면
--   어느 구역이 반이었는지 되짚을 수 없다.
ALTER TABLE study_area
    ADD COLUMN area_type VARCHAR(20) NOT NULL DEFAULT 'STUDY'
        CHECK (area_type IN ('STUDY', 'CLASSROOM')),
    ADD COLUMN class_master_id BIGINT REFERENCES class_master (id);

COMMENT ON COLUMN study_area.area_type IS
    '구역 종류. STUDY=독서실(키오스크가 조회한다) / CLASSROOM=반 교실(키오스크에 내리지 않는다)';
COMMENT ON COLUMN study_area.class_master_id IS
    '반 참조. CLASSROOM 일 때만 채운다 — 반을 만들 때 좌석표를 함께 생성하는 근거';

-- 반 하나에 좌석표는 하나다. 둘이 되면 배치도가 어느 쪽인지 정해지지 않는다.
-- 부분 인덱스라 삭제분은 자리를 차지하지 않는다(구역 코드 유니크와 다르다 — 그쪽은
-- 키오스크 계약 때문에 삭제분도 코드를 붙들고 있어야 한다).
CREATE UNIQUE INDEX uq_study_area_class
    ON study_area (class_master_id)
    WHERE class_master_id IS NOT NULL AND is_deleted = FALSE;

-- CLASSROOM 이면 반이 있어야 하고, STUDY 면 없어야 한다.
-- 없으면 "반 교실인데 어느 반인지 모르는" 행이 조용히 생긴다.
ALTER TABLE study_area
    ADD CONSTRAINT ck_study_area_class_ref CHECK (
        (area_type = 'CLASSROOM' AND class_master_id IS NOT NULL)
        OR (area_type = 'STUDY' AND class_master_id IS NULL)
    );
