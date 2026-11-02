FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 yerag && useradd --uid 10001 --gid yerag --create-home yerag && mkdir -p /app/logs /app/evidence && chown -R yerag:yerag /app
COPY --chown=yerag:yerag target/SmartPAI-0.0.1-SNAPSHOT.jar /app/yerag.jar
COPY config/application.properties /app/config/application.properties
COPY deploy/application-docker.properties /app/config/application-docker.properties
USER yerag
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=docker HOME=/home/yerag
ENTRYPOINT ["java","-jar","/app/yerag.jar"]
