FROM eclipse-temurin:21-jre

COPY target/event-gateway-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","/app.jar"]