# Simplified Dockerfile - assumes target/rate-limiter-*.jar already exists
# This is faster and easier to debug than building Maven inside Docker
#
# Usage:
# 1. mvn clean package -DskipTests  (build on your machine)
# 2. docker build -t rate-limiter:latest .
# 3. docker-compose up -d

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/rate-limiter-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]