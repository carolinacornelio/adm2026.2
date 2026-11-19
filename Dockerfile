# Etapa 1: Build da aplicação Java 21 com Maven
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
COPY front-end-erp ./front-end-erp
RUN mvn package -DskipTests -B

# Etapa 2: Imagem final leve apenas com JRE 21
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
