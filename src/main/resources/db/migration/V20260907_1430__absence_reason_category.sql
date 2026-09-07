-- 사유 카테고리 (앱 시안 피드백 p3 · 3)
--
-- "사유가 표시되는 부분은 대략적인 사유선택에 대한 기본 카테고리가 있거나
--  또는 사유란에 제목을 넣어서 기입(또는 선택)할 수 있어야 할 것 같습니다"
--
-- ■ AbsenceReasonType 과 다른 축이다
--
-- 그쪽은 결석·지각·조퇴·외출 — "무엇을 했는가"다.
-- 여기는 병결·가정사·학교행사 — "왜 그랬는가"다. 같은 결석이라도 사유가 갈린다.
-- 합치면 4종 × 사유 수만큼 값이 늘고, 통계에서 "지각인데 병결"을 못 센다.
--
-- ■ 값을 코드에 박지 않는다
--
-- 어떤 카테고리를 쓸지는 아직 안 정해졌다(클라이언트가 "기본 카테고리가 있거나"라고만 했다).
-- enum 이면 확정될 때마다 마이그레이션을 새로 쓰게 되고, 지점·연도마다 다를 수도 있다.
-- 상벌점 항목(penalty_item)과 같은 방식으로 데이터에 둔다.
--
-- ■ absence_reason.category_id 가 nullable 인 이유
--
-- 카테고리가 하나도 없는 상태에서도 사유 제출이 되어야 한다. 필수로 걸면
-- 값이 확정될 때까지 앱에서 사유를 못 낸다.
CREATE TABLE absence_reason_category (
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

-- 같은 지점·연도에 같은 이름을 두 번 만들지 않는다. 화면 드롭다운에 중복이 뜬다
CREATE UNIQUE INDEX uq_absence_reason_category
    ON absence_reason_category (COALESCE(academy_id, 0), year, name)
    WHERE is_deleted = FALSE;

ALTER TABLE absence_reason
    ADD COLUMN category_id BIGINT REFERENCES absence_reason_category (id);

COMMENT ON TABLE absence_reason_category IS
    '사유 카테고리(병결·가정사 등). AbsenceReasonType(결석·지각·조퇴·외출)과 다른 축이다';
