# Use official Java 17 image
FROM eclipse-temurin:17-jdk-focal

WORKDIR /app

# Copy the built JAR file into the container
COPY target/nimbusboard-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
