-- 계정별 전 지점 입학예약 조회 허용. 화면·API로 편집하지 않고 DB에서만 관리한다.
-- 특정 로그인 ID를 시드하지 않는다. 계정명 변경·DB 이관 시 계정 데이터와 함께 유지한다.
ALTER TABLE account ADD COLUMN all_admission_read BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN account.all_admission_read IS
    'READONLY 단일 역할 계정의 전 지점 입학예약 조회 허용. 기본 FALSE. 다른 업무/쓰기 권한은 부여하지 않음. DB에서만 지정';
