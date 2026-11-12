# 실행 이미지만 만든다. jar 는 먼저 빌드한다(./gradlew bootJar, 배포는 scripts/deploy-on-server.sh 가 Java 25 컨테이너에서 빌드한다).
# Doro SDK 를 형제 디렉터리(../Doro)에서 소스로 빌드하므로 이미지 안에서 빌드하지 않는다(빌드 컨텍스트 밖이다).
FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

RUN addgroup -S doro && adduser -S doro -G doro
USER doro:doro

COPY --chown=doro:doro build/libs/doro-bff-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8085

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8085/actuator/health || exit 1

# IAM 컨테이너가 교체돼 주소가 바뀌어도 곧 따라가도록 JVM 의 DNS 캐시를 짧게 둔다(기본 30초).
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-Dsun.net.inetaddr.ttl=5", "-Dsun.net.inetaddr.negative.ttl=1", "-jar", "app.jar"]
