# order-service + Trace2Local Embedded (SPEC E1/E9)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY . .
RUN mvn -q -pl examples/order-service -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/examples/order-service/target/order-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080 9876
ENTRYPOINT ["java", "-jar", "app.jar"]
