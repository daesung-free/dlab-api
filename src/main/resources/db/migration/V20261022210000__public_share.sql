-- V20261022210000: 공개 공유 링크 (공개 안내문 · 대기자 응답)
--
-- ⚠️ 파일명이 14자리인 이유 — 신규는 밑줄 방식(V<yyyyMMdd>_<HHmm>)이 규칙이지만,
--   이 마이그레이션은 admission_reservation_status_log(V20260918120000, 14자리)를
--   ALTER 한다. 밑줄 방식은 20261022.2100 으로 파싱돼 14자리보다 **항상 먼저** 실행되므로
--   그대로 두면 relation does not exist 로 깨진다(실제로 한 번 깨졌다). CLAUDE.md §7.
--
-- 카카오 알림톡 두 종(「등록비 안내」·「대기자 안내」)에 버튼을 달아야 하는데
-- 붙일 공개 주소가 없었다. 로그인 밖 경로가 도어락 QR 하나뿐이고 나머지는 전부
-- 관리자 인증이라 학부모가 열 수 없다.
--
-- ★ 왜 버튼인가
--   알림톡은 단방향이다. 학생이 답장하면 카카오톡 1:1 채팅으로 들어가고 우리
--   시스템과는 연결되지 않는다 — 지금은 전화를 받아 직원이 손으로 적는다.
--   그 수기 입력을 없애려고 등록 의사를 버튼으로 받는다.

-- ─────────────────────────────────────────────────────────────
-- 공유 토큰
--
-- ★ 대상별로 테이블을 나누지 않는다.
--   안내문·대기자가 같은 모양이다(무엇을 가리키는가 + 언제까지 유효한가).
--   나누면 랜덤 생성·만료 판정·조회 경로를 대상 수만큼 다시 짜게 되고,
--   한 곳만 고치면 다른 쪽이 조용히 다르게 동작한다.
--
-- ★ token 은 추측 불가한 랜덤이다(순번·학생 id 기반 금지).
--   id 를 그대로 쓰면 1 부터 세어 올리는 것만으로 남의 안내문·대기자 페이지가 열린다.
--
-- ★ expires_at 은 서버가 정한다 — 발급 요청에서 기한을 받지 않는다.
--   화면마다 다른 기한을 보내면 「왜 이 링크만 일찍 닫혔나」를 추적할 수 없다.
--   NULL 은 무기한이고, 안내문은 공지의 게시 종료를 그대로 따른다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE public_share_token
(
    id          BIGSERIAL   PRIMARY KEY,
    year        SMALLINT    NOT NULL,

    -- 전 지점 공지는 지점이 없다. 그래서 NULL 을 허용한다
    academy_id  BIGINT      REFERENCES academy (id),

    -- NOTICE · ADMISSION_RESERVATION
    target_type VARCHAR(30) NOT NULL,
    target_id   BIGINT      NOT NULL,

    token       VARCHAR(64) NOT NULL,

    -- NULL = 무기한
    expires_at  TIMESTAMPTZ,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 유니크는 soft delete 를 보지 않는다.
--   지운 토큰의 값이 자리를 계속 차지하는 편이 맞다 — 같은 값을 다시 발급하면
--   폐기한 링크가 되살아난다. 랜덤이라 실제로 겹칠 일도 없다.
CREATE UNIQUE INDEX uq_public_share_token ON public_share_token (token);

CREATE INDEX idx_public_share_target
    ON public_share_token (target_type, target_id, created_at DESC);

COMMENT ON TABLE public_share_token IS
    '로그인 없이 열리는 공개 페이지의 공유 토큰(안내문·대기자 응답)';
COMMENT ON COLUMN public_share_token.expires_at IS
    '서버가 정한다. 발급 요청에서 받지 않는다 — NULL 은 무기한';

-- ─────────────────────────────────────────────────────────────
-- 상태 변경 이력에 「누가 바꿨나」를 더한다
--
-- ★ 학생 응답이 대기자 상태를 바로 바꾼다. 전화를 받아 적는 일을 없애는 것이
--   목적이라 상태를 즉시 반영하되, 직원이 관리자 웹에서 덮을 수 있어야 하고
--   그 변경은 「직원 변경」으로 구분돼 남아야 한다.
--
-- ⚠️ created_by 로는 구분할 수 없다. 공개 경로는 인증 주체가 없어
--   SecurityAuditorAware 가 시스템 계정(0)으로 기록하는데, 배치·DSA 호환 구획도
--   같은 0 이라 「학생이 눌렀다」와 「배치가 했다」가 한 값이 된다.
--
-- 기존 행은 전부 직원 변경이다(공개 경로가 없었다) — 그래서 기본값이 STAFF 다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE admission_reservation_status_log
    ADD COLUMN actor_type VARCHAR(10) NOT NULL DEFAULT 'STAFF',
    -- 공개 경로라 주체가 없어 IP 를 남긴다. 토큰이 새면 남이 누를 수 있고,
    -- 1회 고정이라 피해는 1건으로 끝나지만 그 1건이 어디서 들어왔는지는 보여야 한다
    ADD COLUMN actor_ip   VARCHAR(45);

COMMENT ON COLUMN admission_reservation_status_log.actor_type IS
    'STUDENT(공개 링크 응답) · STAFF(관리자 웹). created_by 로는 구분되지 않는다';

-- ★★ 학생 응답은 1회 고정이다 — 그것을 여기서 보장한다.
--
--   사전 조회만으로는 막히지 않는다. 버튼을 두 번 빠르게 누르면 두 요청이 모두
--   「아직 응답 없음」을 읽고 둘 다 통과한다 — 상벌점 멱등키와 같은 문제이고,
--   결국 DB 제약만이 막는다.
--
--   ⚠️ 토큰을 1회용으로 죽이지는 않는다. 페이지는 계속 열려야 하고 자기가 무엇을
--     눌렀는지 보여야 한다 — 안 그러면 「눌렀는데 아무 일도 없었다」로 다시 열려
--     하고, 그때 페이지가 안 열려 전화가 온다. 그래서 제약은 토큰이 아니라
--     「예약 건당 학생 응답 1건」에 걸린다.
CREATE UNIQUE INDEX uq_admission_status_log_student_answer
    ON admission_reservation_status_log (reservation_id)
    WHERE actor_type = 'STUDENT' AND is_deleted = FALSE;
