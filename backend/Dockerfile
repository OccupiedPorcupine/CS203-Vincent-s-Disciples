# Spring Boot backend image, used by the Vercel "backend" service (see vercel.json).
# Build locally with: docker build -t chicky-backend backend

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Download dependencies in their own layer, so code-only changes rebuild faster.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home app
USER app
WORKDIR /home/app
COPY --from=build /src/app.jar app.jar
COPY --chmod=755 docker-start.sh docker-start.sh
# Vercel routes traffic to PORT. Keep the heap inside the function's memory limit.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["./docker-start.sh"]
