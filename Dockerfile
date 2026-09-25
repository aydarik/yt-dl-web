# Build stage
FROM eclipse-temurin:25-jdk-resolute AS build
WORKDIR /home/gradle/src
COPY . .
RUN ./gradlew bootJar --no-daemon -x test

# Run stage
FROM eclipse-temurin:25-jre-resolute
WORKDIR /app

# Install ffmpeg, deno, yt-dlp
RUN apt-get update && apt-get install -y curl python3 ffmpeg zip \
    && curl -L https://deno.land/install.sh -o deno_install.sh && DENO_INSTALL=/usr/local sh deno_install.sh --yes --no-modify-path && rm deno_install.sh \
    && case "$(uname -m)" in \
         x86_64)  YTDLP_BIN="yt-dlp_linux" ;; \
         aarch64|arm64) YTDLP_BIN="yt-dlp_linux_aarch64" ;; \
         *)       YTDLP_BIN="yt-dlp" ;; \
       esac \
    && curl -L "https://github.com/yt-dlp/yt-dlp/releases/latest/download/${YTDLP_BIN}" -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY --from=build /home/gradle/src/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
