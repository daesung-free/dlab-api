-- 공지 열람 기록 (API_GAPS 13-2)
--
-- 발송 화면의 '열람' 컬럼(281/296)에 채울 값이 없었다. 몇 명이 봤는지가
-- 공지를 다시 보낼지 판단하는 근거인데, 지금은 읽음 기록 자체가 없다.
--
-- ■ 최초 열람 시각만 남긴다
--
-- 다시 볼 때마다 갱신하면 "언제 처음 봤나"가 사라진다. 재열람 횟수는 이 화면이
-- 묻는 값이 아니라서 세지 않는다 — 필요해지면 그때 별도 축으로 붙인다.
--
-- ■ 학생 기준이다
--
-- 학부모가 자녀 화면에서 본 것은 학생이 읽은 것이 아니다. 같은 행으로 세면
-- "학생이 공지를 봤다"는 판단이 흐려진다. 학부모 열람이 필요해지면 열을 나눈다.
CREATE TABLE notice_read (
    id            BIGSERIAL PRIMARY KEY,
    notice_id     BIGINT      NOT NULL REFERENCES notice (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    read_at       TIMESTAMPTZ NOT NULL,

    -- year·branch_id 는 두지 않는다. 공지가 이미 범위(지점·반·개인)를 들고 있어
    -- 여기 또 두면 두 값이 어긋날 수 있고, 어느 쪽이 맞는지 알 방법이 없다
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 학생이 같은 공지를 두 번 세지 않게 막는다. 앱이 화면에 들어올 때마다
-- 호출하는 구조라 중복 호출이 정상 경로다 — 제약이 없으면 열람 수가 부풀어 오른다
CREATE UNIQUE INDEX uq_notice_read ON notice_read (notice_id, enrollment_id);

-- 목록 화면이 공지마다 열람 수를 센다
CREATE INDEX idx_notice_read_notice ON notice_read (notice_id);

COMMENT ON TABLE notice_read IS '공지 열람 기록. 최초 열람 시각만 남긴다';
