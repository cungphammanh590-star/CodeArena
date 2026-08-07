# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package \
    && cp target/business-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S codearena && adduser -S codearena -G codearena
USER codearena
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8090
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
