-- 첨부파일.
--
-- ★ 도메인마다 file_url 컬럼을 두지 않는다. 성적표·재학증명서·사유 증빙·공지 이미지가
--   전부 같은 모양이라, 나눠 두면 검증·삭제·보관기간 처리를 그 수만큼 다시 짜게 된다.
--
-- ★ 버킷 이름은 여기 없다. 공개/비공개만 남기고 실제 버킷은 설정(storage.*)이 정한다 —
--   컷오버 때 학원 계정 버킷으로 바뀌는데 그때 이 테이블을 건드릴 이유가 없어야 한다.
CREATE TABLE file_attachment (
    id            BIGSERIAL    PRIMARY KEY,

    -- 버킷을 가르는 기준. 업로드 후 바뀌지 않는다(바꾸려면 객체를 옮겨야 하고 URL 이 깨진다)
    visibility    VARCHAR(10)  NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),

    -- S3 객체 키. {도메인}/{연도}/{대상id}/{uuid}.{확장자}
    object_key    VARCHAR(500) NOT NULL,

    -- 올린 사람이 보던 이름. 없으면 내려받을 때 파일명이 uuid 가 된다
    original_name VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,

    -- 어디에 붙었는지. ★ enum 이 아니라 문자열이다 — 새 도메인이 파일을 쓸 때마다
    -- 마이그레이션을 새로 쓰게 되면 도메인이 서로를 알게 된다
    owner_type    VARCHAR(50)  NOT NULL,
    -- 대상이 저장되기 전에 파일부터 올리는 화면이 있어 비어 있을 수 있다
    owner_id      BIGINT,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 같은 객체 키가 둘이면 하나를 지울 때 다른 하나가 가리키는 파일이 사라진다.
CREATE UNIQUE INDEX uq_file_attachment_key ON file_attachment (object_key);

-- "이 신청서에 붙은 파일" 조회가 기본 경로다.
CREATE INDEX idx_file_attachment_owner
    ON file_attachment (owner_type, owner_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE file_attachment IS
    '첨부파일. 실제 파일은 S3 에 있고 여기에는 위치와 메타만 남는다';
COMMENT ON COLUMN file_attachment.is_deleted IS
    'soft delete. ★ S3 객체는 함께 지우지 않는다 — "그때 무엇이 제출됐는가"가 성적표 원본 확인·환불 다툼의 근거다. 보관기간 정리는 별도 작업(보관 정책 미확정)';
