FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src/main ./src/main
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# 将编译好的 jar 包复制为 app.jar
COPY --from=build /app/target/diet-agent-1.0-SNAPSHOT.jar ./app.jar
EXPOSE 8080

# 核心补充：追加 --spring.profiles.active=experience 激活体验环境配置
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=experience"]