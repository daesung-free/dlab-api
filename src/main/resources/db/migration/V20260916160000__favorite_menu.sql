-- 자주 쓰는 메뉴 (대시보드 좌측)
--
-- 지금은 프론트 코드에 배열로 박혀 있어 사용자가 바꿀 수 없다. 계정마다 쓰는 화면이
-- 다른데 전원이 같은 목록을 본다.
--
-- ★ 노출 설정(account_menu)과 다른 개념이다.
--   - account_menu : 최고관리자가 "이 계정이 볼 수 있는 메뉴"를 정한다 (권한)
--   - 여기        : 본인이 "자주 가는 메뉴"를 고른다 (편의)
--   섞으면 본인이 자기 권한을 넓히는 꼴이 되므로 테이블을 나눈다.
CREATE TABLE favorite_menu
(
    account_id BIGINT      NOT NULL REFERENCES account (id),
    menu_id    BIGINT      NOT NULL REFERENCES menu (id),

    -- 화면에 놓이는 순서. 사용자가 정한 대로 보여야 한다
    sort_order SMALLINT    NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    PRIMARY KEY (account_id, menu_id)
);

CREATE INDEX idx_favorite_menu_account ON favorite_menu (account_id, sort_order);

COMMENT ON TABLE favorite_menu IS '계정별 자주 쓰는 메뉴. 권한이 아니라 편의 설정이다 — 노출 설정(account_menu)과 섞지 말 것';
