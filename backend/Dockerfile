# Backend image for the free host (DESIGN.md §16: Render free web service, 512 MB RAM).
#   docker build -t vihan-backend backend
#   docker run -p 8080:8080 --env-file backend/.env -e SPRING_PROFILES_ACTIVE=cloud vihan-backend

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Maven Central answers shared build hosts (Render) with 429 Too Many Requests, so Maven itself
# and all dependencies come from Google's official Central mirror (.mvn/docker-settings.xml).
ENV MVNW_REPOURL=https://maven-central.storage-download.googleapis.com/maven2
# Dependencies first, so code changes don't re-download them. go-offline misses the compiler's
# annotation processors, so compile a throwaway class too. The real build then runs offline (-o).
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh mvnw -q -B -s .mvn/docker-settings.xml dependency:go-offline \
 && mkdir -p src/main/java && echo 'class Warmup { public static void main(String[] a) {} }' > src/main/java/Warmup.java \
 && sh mvnw -q -B -s .mvn/docker-settings.xml package -DskipTests \
 && rm -rf src target
COPY src src
RUN sh mvnw -o -q -B -s .mvn/docker-settings.xml package -DskipTests \
 && java -Djarmode=tools -jar target/hospital-*.jar extract --layers --launcher --destination /app

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 vihan
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER vihan
ENV SPRING_PROFILES_ACTIVE=cloud \
    TZ=Asia/Kolkata \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
