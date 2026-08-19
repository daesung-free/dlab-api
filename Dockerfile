# syntax=docker/dockerfile:1
#
# 멀티스테이지 — 빌드 도구(JDK·Gradle·소스)는 최종 이미지에 넣지 않는다.
# JDK 포함 이미지가 ~450MB인 반면 JRE만 남기면 ~200MB다.
#
# 빌드: docker build -t dlab-api .
# 실제 배포 빌드는 GitHub Actions가 한다(.github/workflows/deploy.yml).

# ─────────────────────────────────────────────────────────────
# 1단계: 빌드
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# ★ 의존성 파일만 먼저 복사한다.
#   소스와 함께 복사하면 코드 한 줄만 고쳐도 의존성 레이어가 무효화돼
#   매 빌드마다 전체를 다시 내려받는다(querydsl·POI·Boot 스타터라 양이 많다).
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew

# 의존성 캐시 워밍. 순수 최적화 단계라 실패해도 빌드를 막지 않는다 —
# 여기서 못 받으면 다음 단계에서 어차피 받는다.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src

# -x test — 테스트는 PR에서 ci.yml이 진짜 PostgreSQL·Redis 컨테이너로 이미 돌렸다.
# 여기서 또 돌리려면 이미지 빌드 중에 DB를 띄워야 하는데 그건 불가능하다.
RUN ./gradlew --no-daemon clean bootJar -x test

# build/libs 에는 실행 가능한 boot jar와 라이브러리용 -plain.jar 가 함께 나온다.
# -plain.jar 를 집으면 런타임에 "no main manifest attribute" 로 죽는다.
RUN set -eu; \
    JAR=$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1); \
    test -n "$JAR" || { echo "실행 가능한 jar를 찾지 못했습니다"; ls -la build/libs; exit 1; }; \
    cp "$JAR" /build/app.jar

# ─────────────────────────────────────────────────────────────
# 2단계: 런타임
# ─────────────────────────────────────────────────────────────
# ★ alpine 이 아니라 jammy 다. 이미지가 ~80MB 더 크지만 alpine 을 쓸 수 없다 —
#   eclipse-temurin:17-jre-alpine 은 arm64 이미지가 없어서, x86_64인 GitHub 러너에서는
#   빌드되는데 Apple Silicon Mac에서 로컬 빌드하면 "no match for platform" 으로 깨진다.
#   CI에서만 되고 개발자 기계에서 안 되는 구성은 결국 아무도 로컬에서 확인하지 않게 된다.
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl — compose 헬스체크가 /actuator/health 를 찌른다. 베이스 이미지에 없다.
# tzdata — JDK가 자체 tzdb를 갖고 있어 Java 시각(출결 일자 경계·스케줄러)은 이것 없이도 정확하지만,
#          OS 레벨 시각이 UTC로 남으면 컨테이너 로그 타임스탬프와 앱 로그가 9시간 어긋나
#          장애를 분석할 때 두 로그를 맞춰 볼 수 없다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    # 캐시를 지우지 않으면 apt 인덱스가 그대로 레이어에 남아 이미지가 40MB쯤 커진다.
    && rm -rf /var/lib/apt/lists/*

# ★ root로 돌리지 않는다. 앱이 뚫렸을 때 컨테이너 안에서 할 수 있는 일을 줄인다.
RUN groupadd -r app && useradd -r -g app app

WORKDIR /app
COPY --from=builder --chown=app:app /build/app.jar app.jar
USER app

EXPOSE 8080

# 컨테이너 메모리 한도를 JVM이 인식하고 그 비율만큼 쓴다.
# -Xmx를 숫자로 고정하면 인스턴스를 키워도 힙이 안 늘어난다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul"

# ★ exec 형태여야 java가 PID 1이 되어 SIGTERM을 직접 받는다.
#   sh가 PID 1이면 신호가 전달되지 않아 graceful shutdown 없이 10초 뒤 강제 종료되고,
#   Flyway 마이그레이션 중이었다면 스키마가 반쯤 적용된 채 남는다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
