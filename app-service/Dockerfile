# Build context is the REPO ROOT (see docker-compose.yml), because Maven needs
# mvnw, .mvn/ and the parent pom.xml, all of which live above app-service/.

# ---- stage 1: the workshop — JDK, Maven, sources, dependency cache ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Build descriptors only. Dependency resolution is slow and changes rarely, so it
# gets its own layer ABOVE the source copy and stays cached across code edits.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY app-service/pom.xml app-service/
# The parent pom lists edge-lb as a module, so Maven needs its pom present to build
# the reactor at all — even though -pl app-service never compiles it.
COPY edge-lb/pom.xml edge-lb/
COPY worker/pom.xml worker/
RUN ./mvnw -B -pl app-service -am dependency:go-offline

# Source last: editing a .java file invalidates only from here down.
COPY app-service/src app-service/src
RUN ./mvnw -B -pl app-service -am package -DskipTests

# ---- stage 2: the shipping box — JRE plus the jar, nothing else ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/app-service/target/app-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
