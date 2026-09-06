# Build and run job-radar.
#
# The image is for running this somewhere that stays on. On a laptop the daily
# schedule is unreliable for a reason no code can fix: if the machine is asleep
# at 07:00, nothing runs. See the README.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-resolve them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

# Not root. The process only needs to read its config and write two directories.
RUN useradd --create-home --shell /usr/sbin/nologin jobradar
COPY --from=build /build/target/job-radar-*.jar app.jar

# The database, digests and captured fixtures are the three things worth keeping
# across restarts; mount a volume over /data to make them outlive the container.
ENV JOB_RADAR_DB=jdbc:sqlite:/data/job-radar.db \
    JOB_RADAR_OUT=/data/digests \
    JOB_RADAR_FIXTURE_DIR=/data/fixtures \
    JOB_RADAR_GOOGLE_KEY=/run/secrets/google-key.json \
    JOB_RADAR_SCHEDULE=true \
    TZ=Asia/Kolkata

RUN mkdir -p /data && chown jobradar:jobradar /data
VOLUME ["/data"]
USER jobradar

# `serve` keeps the process alive for the schedule. Override with `run` for a
# one-shot invocation from an external scheduler.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["serve"]
