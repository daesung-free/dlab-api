# 배포 (EC2 + Docker Compose + GitHub Actions)

Actions 탭에서 `Deploy` 워크플로를 **수동 실행**하면 이미지 빌드 → GHCR 푸시 → EC2에서 pull → 컨테이너 교체 → 헬스체크까지 자동으로 진행된다.
이 문서는 **최초 1회 서버 셋업**과 **장애 시 대응**을 다룬다.

> ★ **배포는 자동 트리거가 아니다.** `main` 푸시에 걸어두려 했으나, 이 레포는 private + GitHub Free라
> ruleset·branch protection이 **적용되지 않는다**(Team 플랜부터 가능). `main`을 아무나 밀 수 있는 상태에서
> 머지가 곧 배포이면 실수 한 번이 운영 반영이 되므로, **트리거 자체를 없앴다.**
> Team 플랜으로 올려 `main`을 보호하게 되면 자동 트리거를 되살려도 된다.

> ⚠️ 이 프로젝트는 dev/prod 서버를 따로 두지 않고 **같은 서버를 전환**한다(CLAUDE.md §3).
> 지금 배포 대상은 개발용이며, 실사용 전환은 CLAUDE.md §4 "컷오버 체크리스트"를 의식적으로 거친다.

## 구조

```
GitHub Actions 러너                          EC2 (3.34.25.146)
──────────────────                           ─────────────────────────────────────
docker build (멀티스테이지)                    [네이티브] Nginx  :80 ──┐
      │                                                              │ 127.0.0.1:8080
      ▼                                       ┌──────────────────────▼──────────┐
ghcr.io/daesung-free/dlab-api:<sha>           │ docker compose (dlab-api)       │
      │                                       │                                 │
      └────────── docker compose pull ───────►│  app   ──► db    (볼륨 db_data) │
                  docker compose up -d --wait │        └─► redis (볼륨 redis_data)│
                                              └─────────────────────────────────┘
```

**빌드는 러너에서만 한다.** EC2에서 `docker compose build`를 돌리면 Gradle 컴파일이 메모리를 먹어 돌고 있는 컨테이너가 OOM으로 죽는다. EC2는 이미지를 받기만 한다.

**이미지 전달은 GHCR 경유다.** `docker save | ssh docker load`는 레지스트리 설정이 필요 없는 대신 매 배포마다 300MB+를 통째로 전송한다. GHCR은 바뀐 레이어만 간다.

**앱과 DB 포트는 `127.0.0.1`에만 바인딩한다.** `"8080:8080"`처럼 쓰면 Docker가 iptables를 직접 만져 **보안그룹·UFW와 무관하게 인터넷에 열린다.** Nginx는 같은 호스트에서 프록시하므로 외부 공개가 필요 없다.

**자동 시작·자동 재시작은 compose가 담당한다.** systemd 유닛(`dlab-api.service`)은 더 이상 쓰지 않는다 — `restart: unless-stopped` + Docker 데몬 자동시작이 그 역할을 대신한다.

### 파일 배치

| 레포 | 서버 | 갱신 |
|---|---|---|
| `Dockerfile` | (러너에서만 사용) | — |
| `deploy/docker-compose.yml` | `/home/ubuntu/dlab-api/docker-compose.yml` | **배포마다 덮어씀** |
| `deploy/.env.example` | `/home/ubuntu/dlab-api/.env` | 수동 (덮어쓰지 않음) |

compose 파일을 배포마다 덮어쓰는 이유: 서버에서 손으로 고친 compose와 레포의 compose가 갈라지면 **"레포를 봤는데 서버는 다르게 돌고 있는" 상태**가 되어 장애 분석이 불가능해진다. 서버에서 바꿔야 할 것은 `.env`뿐이다.

---

## 최초 1회 서버 셋업

### 0. 기존 네이티브 구성 정리 (systemd 방식에서 넘어온 경우)

```bash
sudo systemctl disable --now dlab-api 2>/dev/null || true
sudo rm -f /etc/systemd/system/dlab-api.service
sudo systemctl daemon-reload
sudo rm -rf /etc/dlab-api                    # 구 systemd EnvironmentFile
rm -rf /home/ubuntu/dlab-api/releases /home/ubuntu/dlab-api/current.jar
```

네이티브 PostgreSQL/Redis를 제거했다면 **8080·5432·6379를 쓰는 프로세스가 없는지** 먼저 확인한다. 남아 있으면 컨테이너 포트 바인딩이 실패한다.

```bash
sudo ss -tlnp | grep -E ':(8080|5432|6379)'   # 아무것도 안 나와야 한다
```

> ⚠️ **네이티브 PostgreSQL을 제거하면서 데이터도 사라졌다.** 지금은 더미데이터뿐이라 문제가 없고(CLAUDE.md §3 — 개발 DB에 실제 개인정보를 넣지 않는다), 스키마는 Flyway가 첫 기동에 전부 다시 만든다. 이후 단계에서 실제 데이터가 들어간 뒤에는 이 순서가 성립하지 않는다.

### 1. Docker 자동 시작 확인

```bash
sudo systemctl enable --now docker
docker compose version    # v2 여야 한다 (`docker-compose` 하이픈 명령이 아니다)
```

**`enable`을 빠뜨리면 재부팅 후 아무것도 안 뜬다.** compose의 `restart: unless-stopped`는 Docker 데몬이 살아 있을 때만 작동한다.

`ubuntu` 사용자가 `sudo` 없이 docker를 쓸 수 있어야 한다(워크플로가 그렇게 호출한다):

```bash
sudo usermod -aG docker ubuntu   # 적용하려면 SSH 재접속 필요
docker ps                        # 권한 오류 없이 나와야 한다
```

### 2. 디렉터리와 `.env`

```bash
mkdir -p /home/ubuntu/dlab-api
cd /home/ubuntu/dlab-api
vi .env          # 레포의 deploy/.env.example 을 보고 채운다
chmod 600 .env
```

채울 값은 `deploy/.env.example` 참고. `POSTGRES_PASSWORD`와 `JWT_SECRET`은 기본값이 없어 **비어 있으면 앱이 기동에 실패한다** — 의도된 설계다(약한 값으로 조용히 뜨는 것보다 안 뜨는 편이 낫다).

```bash
openssl rand -base64 48    # JWT_SECRET 생성
```

> ⚠️ compose `.env`는 **따옴표를 벗기지 않는다.** `POSTGRES_PASSWORD="abc"`로 쓰면 비밀번호가 `"abc"`가 되어 인증에 실패한다. 따옴표 없이 쓴다.

### 3. Nginx 프록시 대상 확인

기존 설정 그대로 `127.0.0.1:8080`을 가리키면 된다. 컨테이너로 바뀌어도 호스트에서 보이는 주소는 같다.

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

> ★ `X-Forwarded-*`가 없으면 앱이 보는 클라이언트 IP가 전부 `127.0.0.1`이 된다. 로그인 실패 5회 잠금(A-1)처럼 IP 기준 로직이 들어가면 전 사용자가 한 덩어리로 취급된다.

### 4. 첫 배포

Actions 탭 → `Deploy` → **Run workflow** → **브랜치를 `main`으로 선택** → 실행.

> ⚠️ 브랜치 선택 기본값은 레포 기본 브랜치인 `develop`이다. 그대로 누르면
> **CI 검증을 안 거친 코드가 배포된다.** 워크플로 첫 단계가 `main`이 아니면 실패시키지만,
> 실행 전에 확인하는 습관을 들일 것.

첫 배포는 오래 걸린다 — 이미지 빌드(캐시 없음) + PostgreSQL initdb + Flyway 마이그레이션 전체 적용.

### 5. 브랜치 훅 설치 (각 개발자 1회)

`main` 직접 push를 로컬에서 막는다. 서버측 강제가 불가능한 대신이다.

```bash
git config core.hooksPath .githooks
```

`git push --no-verify`로 우회되므로 **강제가 아니라 실수 방지**다. GitHub 웹에서 PR을 머지하는 것은 막히지 않는다 — 그게 정상 경로다.

### 6. DB 계정 확인

`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`는 **볼륨이 비어 있을 때 딱 한 번만 반영된다.** 첫 기동으로 `dlab_dev` DB와 `dlab_app` 계정이 만들어진다.

```bash
docker compose exec db psql -U dlab_app -d dlab_dev -c '\dt'   # 테이블 목록
```

---

## 일상 운영

```bash
cd /home/ubuntu/dlab-api

docker compose ps                      # 상태 (healthy 인지)
docker compose logs -f app             # 앱 로그 실시간
docker compose logs --tail 200 app     # 최근 200줄
docker compose restart app             # 앱만 재시작
docker compose up -d --wait            # .env 변경 후 반영 (latest 이미지로)
docker compose exec db psql -U dlab_app -d dlab_dev
docker stats --no-stream               # 메모리·CPU
```

헬스체크:

```bash
curl localhost:8080/actuator/health
curl localhost:8080/actuator/health/kiosk
```

> ⚠️ **`docker compose down -v`를 습관적으로 쓰지 말 것.** `-v`가 볼륨을 지워 **DB 데이터가 전부 사라진다.** 컨테이너만 내리려면 `down`까지만 쓴다.

---

## 롤백

**자동 롤백은 일부러 넣지 않았다.** 배포는 곧 Flyway 마이그레이션 적용이라, 실패 시점에 스키마는 이미 앞으로 나가 있다. `ddl-auto: validate`인 이전 이미지를 되돌리면 스키마 불일치로 **더 확실하게 죽는다** — 자동 롤백은 상황을 고치는 게 아니라 원인만 가린다.

**마이그레이션이 없는 배포**(코드만 바뀐 경우)라면 이전 이미지로 되돌려도 안전하다:

```bash
cd /home/ubuntu/dlab-api
docker images ghcr.io/daesung-free/dlab-api      # 받아둔 태그 목록
APP_IMAGE=ghcr.io/daesung-free/dlab-api:<이전SHA> docker compose up -d --wait
```

이미지 정리는 `until=24h` 필터로만 하므로 **직전 이미지는 최소 하루 남아 있다.**

**마이그레이션이 포함된 배포**라면 되돌리기 전에 그 마이그레이션이 무엇을 했는지부터 본다. 컬럼 추가처럼 이전 버전과 호환되는 변경이면 위 절차로 되돌려도 되지만, 컬럼 삭제·타입 변경이면 **앞으로 고치는 편이 빠르다**(수정 커밋 → 재배포).

---

## 자주 나오는 실패

| 증상 | 원인 |
|---|---|
| `Could not resolve placeholder 'JWT_SECRET'` | `.env`에 값이 없다. 기본값을 두지 않은 것이 의도다 |
| `password authentication failed for user "dlab_app"` | `.env`의 비밀번호를 나중에 바꿨다. DB 볼륨은 초기값을 유지한다 (아래 "비밀번호 변경") |
| `bind: address already in use` | 네이티브 PostgreSQL/Redis/앱이 아직 살아 있다. `sudo ss -tlnp` 확인 |
| app이 `unhealthy` | `docker compose logs app`. 대개 마이그레이션 실패나 `.env` 누락 |
| `Validate failed: Migration checksum mismatch` | 이미 적용된 마이그레이션 파일을 수정했다. 되돌리고 새 마이그레이션을 추가한다 |
| `Found more than one migration with version` | 두 사람이 같은 타임스탬프로 파일을 만들었다 (CLAUDE.md §7 — 실제로 있었던 사고) |
| `Schema-validation: missing column` | 엔티티는 바뀌었는데 마이그레이션을 안 썼다 |
| GHCR pull에서 `denied` | 서버의 `docker login`이 만료됐다. 워크플로가 매번 새로 로그인하므로 수동 실행 시에만 발생 |
| 재부팅 후 안 뜸 | `systemctl is-enabled docker` 확인 |
| 502 (Nginx는 살아있음) | app 컨테이너가 죽었거나 아직 healthy 전. `docker compose ps` |

### DB 비밀번호 변경

`.env`만 고치면 **앱만 접속에 실패하고 DB는 옛 비밀번호를 유지한다.** 원인이 안 보여서 한참 헤매는 유형이다. DB 안에서 함께 바꾼다:

```bash
docker compose exec db psql -U dlab_app -d dlab_dev -c "ALTER USER dlab_app WITH PASSWORD '새비밀번호';"
vi .env    # POSTGRES_PASSWORD 를 같은 값으로
docker compose up -d --wait
```

---

## 남은 것

- [ ] **`main` 브랜치 보호** — private + Free 플랜이라 ruleset이 적용되지 않는다. 지금은 ①배포 자동 트리거 제거 ②워크플로의 `main` 브랜치 확인 단계 ③로컬 pre-push 훅 셋으로 대신하고 있는데, ③은 설치한 사람에게만 걸리고 `--no-verify`로 우회된다. **Team 플랜(인당 월 $4)으로 올리면** ruleset으로 서버측 강제가 되고, 그때는 자동 트리거를 되살려도 된다.
- [ ] **호스트 키 고정** — 지금은 `ssh-keyscan`으로 매번 받아온다(TOFU). 서버의 `/etc/ssh/ssh_host_ed25519_key.pub`를 `EC2_HOST_KEY` 시크릿에 넣고 그 값으로 `known_hosts`를 채우면 중간자 위험이 사라진다.
- [ ] **Actuator 접근 제한** — `/actuator/health`가 Nginx를 통해 누구나 볼 수 있다. `location /actuator { allow 10.0.0.0/8; deny all; }`로 좁힌다. 컷오버 전 필수.
- [ ] **DB 백업** — 컨테이너 볼륨에 데이터가 있을 뿐 백업은 없다. `pg_dump` cron → S3가 최소선이고, 컷오버 시점에는 RDS로 빼는 편이 낫다(CLAUDE.md §4 체크리스트).
- [ ] **무중단 배포** — app 컨테이너 교체 동안(마이그레이션 포함 수십 초) 502다. 키오스크는 이 구간에 폴백으로 조용히 넘어가므로(CLAUDE.md §3) **장애가 안 보인다.** 트래픽이 실린 뒤에는 인스턴스 2대 + ALB가 필요하다.
- [ ] **배포 알림** — 실패가 Actions 탭 안에서만 보인다.
- [ ] **DB를 컨테이너로 두는 것 자체의 재검토** — 지금은 앱과 같은 EC2 한 대에 DB가 얹혀 있어, 인스턴스가 죽으면 데이터도 같이 위험해진다. 컷오버 전에 RDS 전환을 결정할 것(CLAUDE.md는 원래 RDS 전제다).
