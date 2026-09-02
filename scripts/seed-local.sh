#!/usr/bin/env bash
#
# 로컬 개발 DB에 시드를 넣는다. 여러 번 돌려도 된다(전부 멱등).
#
# ★ 왜 Flyway 가 아니라 스크립트인가
#   db/seed 는 **의도적으로** Flyway 밖에 있다(CLAUDE.md §2). locations 에 추가하면
#     · 시드를 고칠 때마다 체크섬 불일치로 기동이 실패한다 — 시드는 자주 바뀐다
#     · flyway_schema_history 에 기록이 남아, §4 대로 개발 서버가 운영이 될 때
#       prod 프로필에서 "applied but not resolved" 로 validate 가 실패한다
#   그래서 스키마(Flyway)와 데이터(이 스크립트)를 나눠 둔다.
#
# ★ 앱을 한 번 띄운 뒤에 실행할 것 — 테이블이 있어야 들어간다.
#
# 사용:
#   ./scripts/seed-local.sh
#   DB_NAME=dlab_other ./scripts/seed-local.sh
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-dlab_local}"
DB_USER="${DB_USER:-dlab}"
export PGPASSWORD="${DB_PASSWORD:-dlab_local_password}"

SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/main/resources/db/seed"

# ★ 순서가 있다. 지점(academy)이 먼저 들어가야 교습비·급식업체가 그 지점에 붙고,
#   학생도 지점을 참조한다. 파일명 정렬에 맡기지 않고 명시한다.
FILES=(
  "V20260807_1600__academy_seed.sql"        # 지점 11개
  "V20260807_1740__period_seed.sql"         # 교시 마스터 — 없으면 태깅이 전부 code 113 으로 거부된다
  "V20260826_1000__branch_pricing_seed.sql" # 지점별 교습비·급식업체
  "dev_seed.sql"                            # 관리자 2계정 + 학생 60명 (화면 연동 확인용)
  "dev_seed_assignment.sql"                 # 반·좌석·사물함·장학·주소 (배정 화면들이 이게 없으면 빈 화면)
)

# period_master_dev.sql 은 넣지 않는다 — 위 period_seed 가 실제 수령분이라 그쪽이 정본이다.

# psql 이 호스트에 없을 수 있다(맥에서 흔하다). 그러면 docker 컨테이너의 것을 쓴다.
if command -v psql >/dev/null 2>&1; then
  run_sql() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q "$@"; }
  query()   { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tAc "$1"; }
else
  echo "(호스트에 psql 이 없어 docker 컨테이너의 psql 을 씁니다)"
  DC="docker compose exec -T -e PGPASSWORD=$PGPASSWORD postgres psql -U $DB_USER -d $DB_NAME"
  run_sql() { $DC -v ON_ERROR_STOP=1 -q "$@"; }
  query()   { $DC -tAc "$1"; }
fi

if ! query "SELECT 1 FROM academy LIMIT 1" >/dev/null 2>&1; then
  echo "테이블이 없습니다. 앱을 한 번 띄워 마이그레이션을 적용한 뒤 다시 실행하세요."
  echo "  SERVER_PORT=8080 ./gradlew bootRun --args='--spring.profiles.active=local'"
  exit 1
fi

for f in "${FILES[@]}"; do
  echo "▶ $f"
  # 파일은 호스트에 있으므로 stdin 으로 흘려보낸다 — 컨테이너 안에는 없다
  run_sql < "$SEED_DIR/$f"
done

echo
echo "완료. 로그인 계정:"
echo "  admin  / dlab1234!   SUPER_ADMIN  (전 지점)"
echo "  branch / dlab1234!   BRANCH_ADMIN (분당)"
echo
echo "※ 출결·상벌점·상담 등 일부 화면은 전 지점 권한으로 부르면 지점을 지정해야 한다."
echo "  academyId 없이 확인하려면 branch 계정을 쓸 것."
