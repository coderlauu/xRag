FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

COPY backend/pom.xml .
COPY backend/src ./src

RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS runner

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 3001

CMD ["java", "-jar", "app.jar"]
