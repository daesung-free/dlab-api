-- ==========================================================================
-- 학부모 "최대 1인"의 범위를 바로잡는다 — 연락처가 아니라 승인 주체다
--
-- 직전 마이그레이션(V20260805170000)이 student_guardian_link에 학생당 1건 유니크를
-- 걸었는데, 이건 I-12(0803 "학부모 최대 1인")를 과하게 적용한 것이다.
--
-- ★ 실제로 막아야 하는 것은 "승인 요청을 누구에게 보낼지"가 갈리는 상황이다.
--   연락처는 부·모를 따로 보관해야 한다 — DSA getParentHpList가 관계 코드(F/M/E)와 함께
--   목록으로 내리고, 키오스크의 "부모에게 전화 걸기" 화면이 그걸 쓴다.
--   학생당 1건으로 묶으면 그 화면에 번호가 하나만 뜨고, 레거시보다 기능이 줄어든다.
--   ⚠️ 깨지는 게 아니라 조용히 축소되는 종류라 아무도 눈치채지 못한다.
--
-- ★ 이미 적용된 파일을 고치지 않는다 — 체크섬이 깨지면 다른 사람 환경이 기동하지 않는다.
-- ==========================================================================

DROP INDEX IF EXISTS uq_guardian_link_student;


-- 앱 계정을 갖고 승인 요청을 받는 보호자. 학생당 최대 1명이다.
-- 나머지 연결은 연락처 보관용이라 여러 건이어도 무방하다.
ALTER TABLE student_guardian_link
    ADD COLUMN is_approver BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN student_guardian_link.is_approver IS
    '승인 주체 여부(I-12 0803 "학부모 최대 1인"). 앱 가입으로 연결된 학부모가 TRUE.
     FALSE인 연결은 연락처 보관용이며 여러 건 존재할 수 있다.';

-- 부분 유니크 — 승인자만 1명으로 묶고 연락처는 제한하지 않는다.
CREATE UNIQUE INDEX uq_guardian_link_approver
    ON student_guardian_link (student_id) WHERE is_approver;

COMMENT ON INDEX uq_guardian_link_approver IS
    '학생당 승인 주체 1명. 앱 가입 경로가 여럿이라 애플리케이션 검사만으로는 동시 요청을 못 막는다.';


-- 기존 연결분 보정.
-- 지금 존재하는 연결은 전부 학부모 앱 가입으로 만들어진 것이므로(연락처 전용 이관은 아직 없다)
-- 승인자로 표시한다. 직전 유니크 제약 때문에 학생당 1건임이 보장돼 있어 충돌하지 않는다.
UPDATE student_guardian_link SET is_approver = TRUE;
