-- 우선 승인자를 ★ 누가 골랐는지 남긴다 (디랩 1029 · 1031 PM 결정)
--
-- ★★ 지정 주체가 ★ 학생에서 학부모로 바뀐다.
--
--    원래 학생만 고를 수 있었다(dd09a27). 그 근거는 *「학부모가 고르면 학부모를
--    승인자에서 빼는 선택을 학부모가 하게 된다」* 였는데 ★ 반대가 맞다 —
--    ★ 승인권은 학부모의 것이므로 ★ 넘길지도 학부모가 정한다.
--    학생이 「담임」을 고르면 ★ 학부모 모르게 학부모가 승인에서 빠진다.
--
-- ★ 그리고 이 선택은 *「10분 무응답 시 직원에게 승인권이 넘어가는 것에 동의」*
--   기록이다. ★ 그 동의도 학부모가 해야 의미가 있다.
--
-- ★★ 부·모 둘 다 가입한 경우 ★ 마지막에 고른 값이 현재값이다(가장 최근 행 규칙
--    그대로). 그래서 ★ 「누가 골랐나」가 없으면 ★ 다른 보호자에게 알릴 수도,
--    「내가 고른 값이 왜 바뀌었나」에 답할 수도 없다.
--
-- ⚠️ NULL 을 허용한다. ★ 기존 행은 학생이 고른 것이고 ★ 그 계정을 역산할 수 없다
--    (학생 계정이 아니라 「학생 본인」이라는 사실만 안다). 거짓 값을 채우는 것보다
--    ★ 비어 있는 것이 정직하다 — NULL 이 「옛 학생 선택분」을 뜻한다.
--    디랩 1029: *「기존 데이터 이관은 하지 않습니다 — 지금은 테스트 기간」*

ALTER TABLE approver_preference
    ADD COLUMN IF NOT EXISTS chosen_by_account_id BIGINT;

ALTER TABLE approver_preference
    ADD CONSTRAINT fk_approver_preference_chosen_by
        FOREIGN KEY (chosen_by_account_id) REFERENCES account (id);

COMMENT ON COLUMN approver_preference.chosen_by_account_id IS
    '이 값을 고른 계정. 학부모 계정이다. NULL 은 지정 주체가 학부모로 바뀌기 전의 '
    '학생 선택분이다 — 그 계정은 역산할 수 없어 채우지 않는다.';

-- ── 승인자 변경 알림 템플릿 ────────────────────────────────────
--
-- ★★ 채널은 ★ FCM 이다 — 알림톡이 아니다.
--
--    ⚠️ 알림톡은 ★ 사전 심사를 받은 문안만 보낼 수 있고, 등록본 14건에
--    ★ 「승인자 변경」이 없다. 새로 올리면 심사 리드타임이 붙는데,
--    ★ 12월 스토어 심사를 앞두고 그럴 이유가 없다 —
--    ★ 미등원처럼 「놓치면 안 되는」 급이 아니다.
--
-- ★ FCM 은 카카오 심사 대상이 아니라 review_status = 'NOT_REQUIRED' 가 맞다.
--
-- ★★ 그런데 ★ active = FALSE 로 넣는다 — ★ 켜는 것은 별도 결정이다.
--
--    ⚠️ 테스트가 ★ 「아직 실제로 나갈 수 있는 템플릿이 하나도 없어야 한다」를 지킨다.
--    그 가드가 막으려는 것은 ★ 심사 여부가 아니라 ★ 「실수로 나가는 것」이고,
--    실제로 ★ 알림톡 2건이 NOT_REQUIRED 로 새어 들어간 적이 있다.
--
--    ★ FCM 은 심사가 없으니 TRUE 로 두면 ★ 그 순간 발송 가능이 된다. 그런데
--    ⚠️ 지금은 ★ 푸시 토큰이 0건이라(앱 빌드 전, E-7) 어차피 가지 않고,
--    ★ 앱이 나간 뒤에 ★ 사람이 켜는 쪽이 맞다 — penalty_rule.active 와 같은 판단이다.
--
-- ★ 꺼져 있는 동안 발송 경로는 SKIPPED 로 남는다 — ★ 안 간 사실이 기록에 남는다.
--
-- ★ 학생명이 들어 있어 ★ 문구 확정 조건(student_specific)을 만족한다.

INSERT INTO notification_template
    (event_code, channel, recipient_type, title_template, body_template,
     required_variables, content_confirmed, review_status, manual, active)
VALUES
    ('APPROVER_PREFERENCE_CHANGED', 'FCM_PUSH', 'PARENT', '승인자 변경 안내',
     '{studentName} 학생의 승인자가 {before}에서 {after}(으)로 변경되었습니다.',
     'studentName,before,after', TRUE, 'NOT_REQUIRED', FALSE, FALSE)
ON CONFLICT (event_code) DO NOTHING;
