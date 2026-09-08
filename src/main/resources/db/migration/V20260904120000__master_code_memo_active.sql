-- V20260904120000: 기초 마스터 공통 속성 — code · memo · active
--
-- ⚠️ 파일명이 14자리인 이유 — course_type · curriculum · tuition 을 만드는 마이그레이션이
--    전부 14자리다(V20260804170000 · V20260804200000 · V20260805100000).
--    밑줄 방식(V20260904_1200)으로 지으면 20260904.1200 으로 파싱돼 그보다 먼저 실행되고,
--    빈 DB 에서 "relation course_type does not exist" 로 깨진다.
--    이미 테이블이 있는 로컬에서는 통과하므로 새 DB 를 만들 때만 드러난다.
--
-- 학과 · 과정 · 교습비 · 커리큘럼 넷이 지금 (id, academy_id, year, name) 뿐이라
-- 관리 화면에서 할 수 있는 게 이름 바꾸기와 삭제뿐이다.
--
-- ★ code — 이름은 바뀐다("이과" → "자연계열").
--   엑셀 업로드·외부 연동·과거 데이터 대조가 이름으로만 이어져 있으면
--   이름을 고치는 순간 전부 끊긴다. 선택 입력이라 안 쓰면 비워 둔다.
--
-- ★ active — 삭제와 다르다.
--   지난 기수 과정을 지우면 그 과정에 배정됐던 반의 이력이 무엇이었는지 알 수 없다
--   (그래서 삭제도 soft delete다). active=FALSE 는 "새로 고를 수 없다"는 뜻이고
--   이미 그 값을 쓰는 데이터는 그대로 남는다.
--
-- ⚠️ 기존 행은 전부 active=TRUE 로 채운다. 기본값을 FALSE 로 두면
--   운영 중인 마스터가 한꺼번에 사라진다.

ALTER TABLE department_master ADD COLUMN code   VARCHAR(30);
ALTER TABLE department_master ADD COLUMN memo   VARCHAR(200);
ALTER TABLE department_master ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE course_type       ADD COLUMN code   VARCHAR(30);
ALTER TABLE course_type       ADD COLUMN memo   VARCHAR(200);
ALTER TABLE course_type       ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE tuition           ADD COLUMN code   VARCHAR(30);
ALTER TABLE tuition           ADD COLUMN memo   VARCHAR(200);
ALTER TABLE tuition           ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE curriculum        ADD COLUMN code   VARCHAR(30);
ALTER TABLE curriculum        ADD COLUMN memo   VARCHAR(200);
ALTER TABLE curriculum        ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- 코드는 지점·연도 안에서 유일하다.
-- ★ 부분 인덱스라 code 가 NULL 인 행은 제약을 받지 않는다 — 선택 입력이라 대부분 비어 있고,
--   NULL 을 포함시키면 코드 없는 마스터를 하나밖에 못 만든다.
CREATE UNIQUE INDEX uq_department_master_code ON department_master (academy_id, year, code)
    WHERE code IS NOT NULL AND is_deleted = FALSE;
CREATE UNIQUE INDEX uq_course_type_code       ON course_type       (academy_id, year, code)
    WHERE code IS NOT NULL AND is_deleted = FALSE;
CREATE UNIQUE INDEX uq_tuition_code           ON tuition           (academy_id, year, code)
    WHERE code IS NOT NULL AND is_deleted = FALSE;
CREATE UNIQUE INDEX uq_curriculum_code        ON curriculum        (academy_id, year, code)
    WHERE code IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN department_master.code IS '선택. 이름이 바뀌어도 유지되는 키';
COMMENT ON COLUMN department_master.active IS 'FALSE = 새로 고를 수 없음. 기존 데이터는 남는다';
