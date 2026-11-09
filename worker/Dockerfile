# Build context is the REPO ROOT (see docker-compose.yml).

# ---- stage 1: the workshop ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY worker/pom.xml worker/
# Every module declared by the parent pom must have its pom present, or Maven
# cannot build the reactor — even for modules this image never compiles.
COPY app-service/pom.xml app-service/
COPY edge-lb/pom.xml edge-lb/
RUN ./mvnw -B -pl worker -am dependency:go-offline

COPY worker/src worker/src
RUN ./mvnw -B -pl worker -am package -DskipTests

# ---- stage 2: the shipping box ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/worker/target/worker-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
