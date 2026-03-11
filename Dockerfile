# Build stage
FROM gradle:jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle build --no-daemon -x test

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install ffmpeg, deno, yt-dlp
RUN apt-get update && apt-get install -y curl python3 ffmpeg zip \
    && curl -L https://deno.land/install.sh -o deno_install.sh && DENO_INSTALL=/usr/local sh deno_install.sh --yes --no-modify-path && rm deno_install.sh \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY --from=build /home/gradle/src/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
