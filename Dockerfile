# --- STAGE 1: BUILD THE JAR ---
FROM maven:3.9-eclipse-temurin-17-focal AS build
WORKDIR /app

# Copy pom.xml and download dependencies first (caching layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# --- STAGE 2: RUN THE APP ---
FROM eclipse-temurin:17-jdk-focal
WORKDIR /app

# Copy the JAR from the build stage
COPY --from=build /app/target/nimbusboard-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
