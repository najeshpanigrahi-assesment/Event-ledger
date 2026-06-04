FROM eclipse-temurin:21-jre

COPY target/account-service-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java","-jar","/app.jar"]