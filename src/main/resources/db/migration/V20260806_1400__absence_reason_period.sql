-- V20260806_1400: 사유신청 시간 범위
--
-- ★ 화면(AbsenceRequest.tsx)의 "기간" 컬럼이 시각을 요구한다:
--     외출  "13:00 ~ 15:00"   시작·종료 둘 다
--     조퇴  "16:30 이후"       시작만
--     결석·지각 "종일"          둘 다 없음
--   지금은 attendance_date(일자)만 있어서 표시할 수가 없다.
--
-- ★ 정기일정 인정 판정이 이 값을 쓴다 — "등록 시간 대비 실제 출입 30분 이상 차이 시 미인정"
--   (요구사항 3시트 계산식). 일자만으로는 판정이 불가능하다.

ALTER TABLE absence_reason
    ADD COLUMN start_time TIME,
    ADD COLUMN end_time   TIME;

COMMENT ON COLUMN absence_reason.start_time IS
    '외출·조퇴 시작 시각. 결석·지각은 종일이라 NULL';
COMMENT ON COLUMN absence_reason.end_time IS
    '외출 종료 시각. 조퇴는 복귀가 없어 NULL(화면에 "N시 이후"로 표시)';

-- 관리자 목록용 인덱스는 V1의 idx_absence_reason_academy_date를 그대로 쓴다.
-- 같은 이름으로 다시 만들면 마이그레이션이 통째로 실패한다.
