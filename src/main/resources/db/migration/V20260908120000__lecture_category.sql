-- 특강 유형 세분류 (F-4.10-4 · 화면의 '유형' 컬럼)
--
-- 화면은 특강을 단과·실전·해설로 나누는데 서버에는 그 축이 없었다.
--
-- ■ LectureType 과 다른 축이다
--
-- 그쪽은 특강 / 설명회 — 화면의 **탭**이다.
-- 여기는 단과·실전·해설 — 그 특강이 **어떤 종류인가**다. 설명회에는 안 붙는다.
-- 합치면 탭이 5개가 되고, "설명회 중 해설" 같은 없는 조합이 생긴다.
--
-- ■ 값을 코드에 박지 않는다
--
-- 발주 회신이 "추가할 수 있게"였다. enum 이면 하나 늘 때마다 마이그레이션을 새로 쓰고
-- 그때마다 배포해야 한다. 지점·연도마다 다를 수도 있어 absence_reason_category ·
-- penalty_item 과 같은 방식으로 데이터에 둔다.
--
-- ■ lecture.category_id 가 nullable 인 이유
--
-- 카테고리가 하나도 없는 상태에서도 특강 등록이 되어야 한다. 필수로 걸면
-- 값이 채워질 때까지 특강을 못 만든다. 설명회는 애초에 세분류가 없어 계속 비어 있다.
--
-- ⚠️ 파일명이 14자리인 이유 — lecture 를 만드는 V20260806140000 이 14자리라,
--    밑줄 방식(V20260908_1200)은 20260908.1200 으로 파싱돼 **먼저** 실행된다.
--    빈 DB 에서 "relation lecture does not exist" 로 깨진다.

CREATE TABLE lecture_category (
    id          BIGSERIAL   PRIMARY KEY,
    -- NULL 이면 전 지점 공통. 지점마다 다르게 쓰면 그 지점만 채운다
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

-- 같은 지점·연도에 같은 이름을 두 번 만들지 않는다 — 드롭다운에 중복이 뜬다
CREATE UNIQUE INDEX uq_lecture_category
    ON lecture_category (COALESCE(academy_id, 0), year, name)
    WHERE is_deleted = FALSE;

ALTER TABLE lecture
    ADD COLUMN category_id BIGINT REFERENCES lecture_category (id);

COMMENT ON TABLE lecture_category IS
    '특강 유형 세분류(단과·실전·해설). LectureType(특강/설명회)과 다른 축이다';
