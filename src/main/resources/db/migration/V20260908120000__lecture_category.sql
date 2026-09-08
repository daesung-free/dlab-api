-- V20260908120000: 특강 유형 마스터 — 단과 · 실전 · 해설 …
--
-- ⚠️ 파일명이 14자리인 이유 — lecture 테이블을 만드는 V20260806140000 이 14자리다.
--    밑줄 방식으로 지으면 그보다 먼저 실행돼 빈 DB 에서 깨진다.
--    V20260903141000(강사) · V20260907120000(코드)이 같은 이유다.
--
-- ■ LectureType 과 다른 축이다
--
-- LectureType 은 특강이냐 설명회냐다 — 흐름이 같아 한 테이블에 두고 구분만 하는 값이라
-- 늘어날 일이 없다. 여기는 특강 안에서의 성격(단과·실전·해설)이고 학원이 늘린다.
-- 합치면 "설명회 · 단과 · 실전"이 한 목록에 섞여 화면이 무엇을 고르는 자리인지 흐려진다.
--
-- ■ enum 에 박지 않는 이유
--
-- 클라이언트가 "세 가지 고정이 아니라 관리자가 추가할 수 있게" 요청했다.
-- enum 이면 유형을 하나 늘릴 때마다 마이그레이션을 새로 쓰고 배포해야 한다.
-- 상벌점 항목 · 사유 카테고리와 같은 방식으로 데이터에 둔다.
--
-- ■ nullable 인 이유
--
-- 유형이 하나도 등록되지 않은 상태에서도 특강을 열 수 있어야 한다.
-- 필수로 걸면 마스터를 채우기 전까지 특강 개설이 막힌다.
-- 이미 등록된 특강도 유형이 없는 채로 남는다.
CREATE TABLE lecture_category (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      REFERENCES academy (id),
    year        SMALLINT    NOT NULL,
    name        VARCHAR(50) NOT NULL,
    sort_order  SMALLINT    NOT NULL DEFAULT 0,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 지점·연도에 같은 이름을 두 번 만들지 않는다. 드롭다운에 중복이 뜬다
CREATE UNIQUE INDEX uq_lecture_category
    ON lecture_category (COALESCE(academy_id, 0), year, name)
    WHERE is_deleted = FALSE;

ALTER TABLE lecture ADD COLUMN category_id BIGINT REFERENCES lecture_category (id);

COMMENT ON TABLE lecture_category IS
    '특강 유형(단과·실전·해설 등). LectureType(특강/설명회)과 다른 축이다';
