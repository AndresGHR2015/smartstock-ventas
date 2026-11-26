FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY vendor/smartstock-infra/contracts/proto ./vendor/smartstock-infra/contracts/proto
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/sales-1.0.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
