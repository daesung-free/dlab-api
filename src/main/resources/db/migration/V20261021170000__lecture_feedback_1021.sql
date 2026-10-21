-- 특강 — 디랩 1021 답변 반영 (1016 Q1·Q2·Q3 의 답).
--
-- ① 수강료 = 회차당 단가 (Q1 "(가) 형태로 준비를 부탁드립니다")
-- ② 수강 대상 = 계열 + 학년 둘 다 복수 선택 (Q2 "병행")
-- ③ 대기 인원 = 막되 대기로 넘긴다 (Q3 "(가) 로 준비하되 대기인원으로 유입될 수 있도록")

-- ─────────────────────────────────────────────────────────────
-- ① 회차당 단가
--
-- ★ 비면 lecture.fee 를 n등분하지 않는다 — 「미설정」으로 둔다.
--   등분하면 나누어떨어지지 않는 회차에서 합계가 정가와 어긋나고, 그 차이를
--   누가 부담하는지가 코드에만 남는다.
ALTER TABLE lecture_session
    ADD COLUMN fee INTEGER;

COMMENT ON COLUMN lecture_session.fee IS
    '그 회차 수강료. NULL 은 미설정이다 — lecture.fee 를 n등분하지 말 것';

-- ─────────────────────────────────────────────────────────────
-- ② 수강 대상 2축
--
-- ★★ 「전체」를 행으로 만들지 않는다. 두 축 모두 비면 제한 없음이다.
--   「전체」행과 「자연+인문 둘 다 체크」가 같은 뜻인데 데이터가 두 가지로 갈린다.
--
-- ★ 디랩 원문: "논술 특강 같은 경우는 인문논술, 자연논술로 나뉘기 때문에
--   학년/계열이 모두 필요합니다."
CREATE TABLE lecture_target_track
(
    id         BIGSERIAL PRIMARY KEY,
    lecture_id BIGINT      NOT NULL REFERENCES lecture (id),
    track      VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_lecture_target_track ON lecture_target_track (lecture_id, track);

CREATE TABLE lecture_target_grade
(
    id         BIGSERIAL PRIMARY KEY,
    lecture_id BIGINT      NOT NULL REFERENCES lecture (id),
    grade      VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_lecture_target_grade ON lecture_target_grade (lecture_id, grade);

COMMENT ON TABLE lecture_target_track IS
    '수강 대상 계열. 비어 있으면 제한 없음 — 「전체」를 행으로 만들지 말 것';
COMMENT ON TABLE lecture_target_grade IS
    '수강 대상 학년. 비어 있으면 제한 없음. GradeType 값(N_SU/HIGH3/HIGH2/HIGH1)이다';

-- ─────────────────────────────────────────────────────────────
-- ③ 대기 인원 상한
--
-- ★ 정원이 차면 신청을 막고 그 자리에서 대기로 넘긴다 — 별도 화면으로 보내지 않는다.
--   대기 전환 자체는 이미 돌고 있다(ApplicationStatus.WAITLISTED). 없던 것은 상한이다.
ALTER TABLE lecture
    ADD COLUMN waitlist_capacity INTEGER;

COMMENT ON COLUMN lecture.waitlist_capacity IS
    '대기 인원 상한. NULL 이면 무제한 — 정원(capacity)과 같은 규약이다';
