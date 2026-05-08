FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

COPY common/pom.xml ./common/pom.xml
COPY common/src ./common/src
RUN mvn -f common/pom.xml install -DskipTests -B

COPY expense-service/pom.xml ./expense-service/pom.xml
RUN mvn -f expense-service/pom.xml dependency:go-offline -B

COPY expense-service/src ./expense-service/src
RUN mvn -f expense-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/expense-service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
