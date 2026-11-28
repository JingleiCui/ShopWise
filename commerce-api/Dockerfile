FROM eclipse-temurin:17-jdk AS build
WORKDIR /source
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /source/target/commerce-api-1.0.0.jar app.jar
USER 65532:65532
ENV BIND_ADDRESS=0.0.0.0 SPRING_PROFILES_ACTIVE=prod
EXPOSE 20174
ENTRYPOINT ["java","-jar","app.jar"]
