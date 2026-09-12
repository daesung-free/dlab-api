-- 같은 사람이 같은 지점·같은 연도에 활성 등록을 두 개 가질 수 없다.
--
-- ★ 저장 버튼을 두 번 누르면 학생이 두 명 생기던 것의 근본 대응이다.
--   그동안은 "최근 10초 안에 같은 내용이 있나" 를 애플리케이션에서 읽어 판단했다.
--   시간 창은 기준이 임의적이고, 읽기와 쓰기 사이가 비어 동시 요청에 뚫렸다.
--   여기서는 DB 가 심판이 된다 — 학번 채번이 이미 쓰는 방식과 같다.
--
-- ★ WHERE is_current 가 핵심이다. 전체에 걸면 퇴원생 재등록(F27-6)이 막힌다.
--   퇴원하면 is_current 가 false 가 되므로, 같은 해에 다시 등록해도 통과한다.
--   막는 것은 "같은 사람이 동시에 두 번 재원 중" 인 상태뿐이다.
--
-- ★ 사람(student) 이 아니라 등록 건(student_enrollment) 에 건다.
--   이름·생년월일·연락처는 student 에 있고 지점·연도는 enrollment 에 있어
--   한 테이블 제약으로는 표현할 수 없다. 대신 접수 시 사람을 먼저 찾아
--   같은 사람이면 student_id 가 같아지고, 그때 이 제약이 걸린다.
--
-- ⚠️ 이 제약만으로 전부 막히지는 않는다. 이름·생년월일·연락처 중 하나라도 비면
--   서버가 같은 사람인지 판단할 수 없어 다른 student 로 들어간다 —
--   그 구간은 애플리케이션의 자문 잠금 + 시간 창이 계속 맡는다.
CREATE UNIQUE INDEX uq_enrollment_active_person
    ON student_enrollment (student_id, academy_id, year)
    WHERE is_current AND NOT is_deleted;

COMMENT ON INDEX uq_enrollment_active_person IS
    '같은 사람의 활성 등록은 지점·연도당 하나. 퇴원(is_current=false) 후 재등록은 허용된다.';
