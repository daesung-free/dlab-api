-- 계정별 메뉴 노출 설정 (0914 확정)
--
-- ★ 역할 단위가 아니라 계정 단위다. "조회 전용 계정 하나하나에 대해 최고관리자가 보여질
--   메뉴를 고른다" 가 확정 사항이다. 역할로 일괄 제한하는 구조로 만들면 같은 READONLY
--   계정끼리 다르게 줄 수가 없다.
--
-- ★★ 화면만 감추는 것으로는 부족하다. 주소를 직접 치면 그대로 열리므로 서버가 같은
--    설정으로 막아야 한다 — 그래서 메뉴에 경로(path_prefix)를 들려 둔다.

-- 메뉴 카탈로그. 지점·연도와 무관한 전역 마스터라 role 과 같이 공통컬럼 일부를 두지 않는다
CREATE TABLE menu
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,

    -- 묶어서 보여주기 위한 상위 메뉴. 없으면 최상위다
    parent_code VARCHAR(50),

    -- ★ 서버가 막을 기준. 이 접두사로 시작하는 요청이 이 메뉴에 속한다.
    --   비워 두면 화면에서만 감춰지고 서버는 막지 않는다 — 목록 API 가 그 사실을 드러낸다
    path_prefix VARCHAR(200),

    sort_order  SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 계정에 허용된 메뉴.
-- ★ 행이 하나도 없으면 "제한 없음"이다(역할 권한 그대로). "아무것도 못 봄"이 아니다 —
--   그렇게 두면 설정을 만들지 않은 기존 계정이 전부 잠긴다.
CREATE TABLE account_menu
(
    account_id BIGINT      NOT NULL REFERENCES account (id),
    menu_id    BIGINT      NOT NULL REFERENCES menu (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    PRIMARY KEY (account_id, menu_id)
);

CREATE INDEX idx_account_menu_account ON account_menu (account_id);

COMMENT ON TABLE menu IS '관리자 웹 메뉴 카탈로그. code 는 프론트 메뉴 식별자와 같아야 한다';
COMMENT ON COLUMN menu.path_prefix IS '서버 차단 기준 경로. 비면 서버는 막지 않는다';
COMMENT ON TABLE account_menu IS '계정별 허용 메뉴. 행이 없으면 제한 없음(역할 권한 그대로)';

INSERT INTO menu (code, name, parent_code, path_prefix, sort_order) VALUES
    ('student',              '학생 관리',        NULL,      '/api/v1/admin/students', 10),
    -- ★ 경로가 비어 있는 메뉴 — 화면은 있는데 서버 API 가 아직 없다.
    --   코드가 없으면 프론트가 그 화면을 거를 수단이 없어서 미리 넣어 둔다.
    --   API 가 생기면 그때 path_prefix 만 채우면 서버 차단까지 함께 걸린다
    ('waiting',              '대기자 관리',      'student', NULL, 14),
    ('timetable',            '시간표·이동수업',  'student', NULL, 15),
    ('personal-record',      '신상기록부',       'student', NULL, 16),
    ('student-signup',       '가입 승인',        'student', '/api/v1/admin/student-signups', 11),
    ('app-account',          '앱 계정',          'student', '/api/v1/admin/app-accounts', 12),
    ('app-config',           '앱 운영 관리',     NULL,      '/api/v1/admin/app-config', 97),
    ('class',                '반 관리',          'student', '/api/v1/admin/classes', 13),

    ('attendance',           '출결 현황',        NULL,      '/api/v1/admin/attendance', 20),
    ('absence',              '사유 신청',        'attendance', '/api/v1/admin/absence-requests', 21),
    ('schedule',             '정기일정',         'attendance', '/api/v1/admin/schedules', 22),
    ('approval',             '승인 관리',        'attendance', '/api/v1/admin/approvals', 23),
    ('firewall',             '와이파이 해제',    'attendance', '/api/v1/admin/firewall-requests', 24),

    ('penalty',              '상벌점',           NULL,      '/api/v1/admin/penalties', 30),
    ('routine',              '데일리루틴',       NULL,      '/api/v1/admin/routines', 31),
    ('learning-plan',        '학습계획',         NULL,      '/api/v1/admin/learning-plans', 32),

    ('billing',              '수납 관리',        NULL,      '/api/v1/admin/billings', 40),
    ('tuition',              '교습비',           'billing', '/api/v1/admin/tuition', 41),
    ('payment-request',      '결제 요청',        'billing', '/api/v1/admin/payment-requests', 42),
    ('cash-receipt',         '현금영수증',       'billing', '/api/v1/admin/cash-receipts', 43),
    ('scholarship',          '장학',             'billing', '/api/v1/admin/scholarship', 44),

    ('meal',                 '급식',             NULL,      '/api/v1/admin/meals', 50),
    ('meal-vendor',          '급식업체·단가',    'meal',    '/api/v1/admin/meal-vendors', 51),

    ('grade',                '성적',             NULL,      '/api/v1/admin/grades', 60),
    ('consult',              '상담',             NULL,      '/api/v1/admin/consults', 61),
    ('lecture',              '특강',             NULL,      '/api/v1/admin/lectures', 62),

    ('notice',               '공지',             NULL,      '/api/v1/admin/notices', 70),
    ('qna',                  '질의응답',         'notice',  '/api/v1/admin/qna', 71),
    ('survey',               '설문',             'notice',  '/api/v1/admin/surveys', 72),
    ('notification',         '알림 발송',        'notice',  '/api/v1/admin/notification-logs', 73),

    ('seat',                 '좌석·구역',        NULL,      '/api/v1/admin/seats', 80),
    ('statistics',           '통계',             NULL,      '/api/v1/admin/statistics', 81),

    ('master',               '기초관리',         NULL,      '/api/v1/admin/masters', 90),
    ('holiday',              '공휴일',           'master',  '/api/v1/admin/holidays', 91),
    ('period',               '교시',             'master',  '/api/v1/admin/periods', 92),
    ('branch-config',        '지점 설정',        'master',  '/api/v1/admin/branch-configs', 93),
    ('staff',                '직원 계정',        'master',  '/api/v1/admin/staff', 94),
    ('pg-site',              '결제 사이트코드',  'master',  '/api/v1/admin/pg-sites', 95),
    ('audit-log',            '감사 로그',        'master',  '/api/v1/admin/audit-logs', 96);
