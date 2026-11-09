# Build context is the REPO ROOT (see docker-compose.yml): Maven needs mvnw,
# .mvn/ and the parent pom.xml, all of which live above edge-lb/.

# ---- stage 1: the workshop ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY edge-lb/pom.xml edge-lb/
# The parent pom lists app-service as a module, so Maven needs its pom present to
# build the reactor at all — even though -pl edge-lb never compiles it.
COPY app-service/pom.xml app-service/
COPY worker/pom.xml worker/
RUN ./mvnw -B -pl edge-lb -am dependency:go-offline

COPY edge-lb/src edge-lb/src
RUN ./mvnw -B -pl edge-lb -am package -DskipTests

# ---- stage 2: the shipping box ----
# No fat jar needed: edge-lb has zero runtime dependencies, so the plain
# class files on the classpath are the whole application.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/edge-lb/target/classes /app/classes
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/classes", "com.quicklink.edge.EdgeLb"]
