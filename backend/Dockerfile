# Spring Boot backend image, used by the Vercel "backend" service (see vercel.json).
# Build locally with: docker build -t chicky-backend backend

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# Download dependencies in their own layer, so code-only changes rebuild faster.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home app
USER app
WORKDIR /home/app
# Vercel routes traffic to PORT and gives the container about 25 s to open it, on a fraction
# of a CPU, so the JVM is tuned for starting fast rather than for peak speed:
# - MaxRAMPercentage keeps the heap inside the function's memory limit;
# - TieredStopAtLevel=1 compiles with the quick C1 compiler only;
# - SerialGC avoids starting garbage collector threads that a fraction of a CPU can't use.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
# Class Data Sharing (Spring Boot's "CDS" support): unpack the jar, start the app once so the JVM
# records the classes it loads (spring.context.exit=onRefresh stops it as soon as the context is
# up, on the in-memory H2 database, before anything calls the network), and keep that archive
# for every later start. It has to be made by this image's JVM, so it's made here, not in build.
COPY --from=build /src/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application && rm app.jar \
    && java -XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh \
       -Dapp.assistant.provider=offline -jar application/app.jar > /dev/null
COPY --chmod=755 docker-start.sh docker-start.sh
EXPOSE 8080
CMD ["./docker-start.sh"]
