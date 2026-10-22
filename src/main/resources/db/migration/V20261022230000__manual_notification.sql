-- 직원 수동 발송 (디랩 1016 문구 3장 — 「직원 발송」 4종).
--
-- ⚠️ 파일명이 14자리인 이유는 V20261016110000 과 같다. 밑줄 방식(V20261022_2000)은
--    Flyway 가 `20261022.2000` 으로 파싱해 **이미 적용된 `20261022120000`
--    (dvoca_vendor_answer) 보다 작아진다.** 운영은 out-of-order 를 막으므로 배포가 실패한다.
--
-- ★ 지금까지 알림은 **이벤트가 일어나야 나가는 구조**뿐이었다. 템플릿이 이벤트당 하나
--   (event_code UNIQUE)라 「수동 발송 전용 템플릿」이라는 것이 서버에 없었고, 1016 문구
--   13종 중 4종(대기자·급식 마감·등록비 안내·등록비 미납)이 전부 직원 발송이다.
--
-- ★ 수동 발송 대상은 **서버가 가린다**(manual 컬럼). 화면이 이벤트 이름으로 추측하면
--   템플릿이 늘 때마다 화면을 고쳐야 한다.

-- ── 1. 템플릿의 manual 축 ──────────────────────────────────────
--
-- ★ 발송 가능(sendable)과 다른 축이다. manual 은 「직원이 때를 골라 보내는 문구인가」,
--   sendable 은 「지금 실제로 나가는가」다 — 합치면 심사 대기 중인 수동 템플릿이
--   목록에서 사라져 담당자가 「급식 안내가 왜 없지」를 겪는다.

ALTER TABLE notification_template
    ADD COLUMN manual BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN notification_template.manual IS
    '직원이 발송 시점을 골라 보내는 템플릿. 이벤트 트리거가 없다(디랩 1016 3장).';

-- ── 2. 직원 발송 템플릿 3종 ────────────────────────────────────
--
-- ⚠️ 대기자 안내톡(1016 7번)은 넣지 않는다. **입학 대기자 도메인이 아직 없고**
--    (F-4.2-1 이 대성전산 입학예약폼 대기), 특강 대기자와 어느 쪽인지도 확정되지 않았다.
--    대상을 고를 수 없는 템플릿을 미리 넣으면 목록에 뜨는데 아무도 보낼 수 없다.
--
-- ★ review_status 를 DRAFT 로 둔다 — 카카오에 등록·심사 전이다. NOT_REQUIRED 로 두면
--   심사 게이트를 그냥 통과해 등록도 안 된 템플릿으로 발송을 시도하고 카카오가 거절한다.
--
-- ⚠️ **급식 마감·등록비 안내는 content_confirmed 를 TRUE 로 올릴 수 없다.** 디랩 문구에
--    학생명이 없는데 우리 규칙은 본문에 {studentName} 을 요구한다(다자녀 학부모가
--    「이거 누구 얘기지」를 겪지 않게 하려고 넣은 규칙이다). 문구에 학생명을 넣어도
--    되는지 **디랩에 확인해야 하고**, 그때까지 이 두 건은 sendable = false 로 목록에만 뜬다.
--    ★ 그래도 목록에서 빼지 않는다 — 빼면 「왜 없지」가 되고, 고를 수 있게 두면
--      보냈는데 안 나간다. 화면이 보여주되 고르지 못하게 한다.

INSERT INTO notification_template
    (event_code, channel, recipient_type, title_template, body_template,
     required_variables, content_confirmed, review_status, manual)
VALUES
    -- 8번: [월] 급식 신청이 [날짜]에 마감됩니다.
    ('MEAL_APPLY_DEADLINE', 'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {월} 급식 신청이 {날짜}에 마감됩니다.',
     '월,날짜', FALSE, 'DRAFT', TRUE),

    -- 9번: [기간] [월] 등록기간입니다. [안내문 링크]
    -- ★ 본문 링크는 버튼으로 뺄 것을 제안해 둔 상태다 — 본문 URL 은 카카오 심사에서 걸린다.
    ('TUITION_REGISTRATION_NOTICE', 'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {기간} {월} 등록기간입니다.',
     '기간,월', FALSE, 'DRAFT', TRUE),

    -- 10번: [학생명] [월] 등록비가 미납중 입니다.
    -- 이 건만 학생명이 들어 있어 문구 확정 조건을 만족한다.
    ('TUITION_UNPAID_NOTICE', 'KAKAO_ALIMTALK', 'PARENT', '',
     '[DLab] {studentName} 학생 {월} 등록비가 미납중 입니다.',
     '월', TRUE, 'DRAFT', TRUE);

-- ── 3. 미리보기 ────────────────────────────────────────────────
--
-- ★★ **미리보기를 거치지 않으면 발송할 수 없다.** 그래서 대상·변수를 여기에 **스냅샷**으로
--    박아 두고, 발송 요청은 previewId 하나만 받는다.
--    조건(scope·enrollmentIds)을 발송 본문에 다시 싣게 하면 화면이 미리보기 뒤에 대상을
--    바꿔도 서버는 모르고, **담당자가 본 것과 다른 사람에게 나간다.**
--
-- ★ 유효기간 10분. 창을 열어 둔 사이 퇴원한 학생이 섞인다.

CREATE TABLE notification_manual_preview (
    id            BIGSERIAL    PRIMARY KEY,
    -- 공개 식별자. 연속 숫자를 그대로 주면 남의 미리보기를 추측할 수 있다
    preview_id    UUID         NOT NULL UNIQUE,
    template_id   BIGINT       NOT NULL REFERENCES notification_template (id),
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),
    year          SMALLINT     NOT NULL,
    -- BRANCH / CLASS / SELECTED.
    -- ⚠️ 대기자(WAITLIST)는 일부러 빼 뒀다 — 대기자 도메인이 없다(위 2장 참고).
    --    나중에 축을 더할 수 있게 CHECK 로만 묶고 enum 타입은 만들지 않는다.
    scope         VARCHAR(20)  NOT NULL CHECK (scope IN ('BRANCH', 'CLASS', 'SELECTED')),
    class_id      BIGINT       REFERENCES class_master (id),
    -- 직원이 채운 변수(월·기간·날짜 등). 발송 시점에 이 값을 그대로 쓴다
    variables     JSONB        NOT NULL,
    -- 확정된 수신자 스냅샷. [{enrollmentId, studentId, accountId, receiverName}, ...]
    targets       JSONB        NOT NULL,
    target_count  INTEGER      NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_manual_preview_academy
    ON notification_manual_preview (academy_id, created_at DESC);

-- ── 4. 발송 — previewId 1회성을 DB 가 보장한다 ─────────────────
--
-- ★★ **코드로만 막으면 두 통이 간다.** 버튼이 두 번 눌리거나 새로고침되면 같은 학부모에게
--    같은 알림톡이 두 번 나가고, 알림톡은 **건당 과금**이다. 같은 판단으로 이미
--    uq_penalty_point_idempotency 가 있다 — 사전 조회는 다중 인스턴스 동시 요청을 막지
--    못하고, 막는 것은 DB 유니크 제약뿐이다.
--
-- ★ 소비 플래그(consumed_at) 로 두지 않고 **별도 행**으로 둔 이유: 플래그는 UPDATE 라
--   조건을 빠뜨리면 조용히 두 번 통과하지만, 행은 INSERT 가 **반드시** 유니크에 걸린다.
--   덤으로 「누가 언제 보냈나」가 남는다.

CREATE TABLE notification_manual_send (
    id           BIGSERIAL    PRIMARY KEY,
    -- ★★ UNIQUE 가 1회성의 전부다. 지우지 말 것
    preview_id   BIGINT       NOT NULL UNIQUE
                 REFERENCES notification_manual_preview (id),
    academy_id   BIGINT       NOT NULL REFERENCES academy (id),
    year         SMALLINT     NOT NULL,
    requested    INTEGER      NOT NULL,
    skipped      INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE
);

COMMENT ON COLUMN notification_manual_send.preview_id IS
    '미리보기 1건당 발송 1회. UNIQUE 가 중복 발송을 막는다 — 알림톡은 건당 과금이다.';
