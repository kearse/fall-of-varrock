# Kingdom of Lumbridge — game server runtime image.
#
# The distribution must be built BEFORE this image (CI does this; locally):
#   ./gradlew game-server:installDist
#
# The Launcher resolves paths relative to its working directory (bin/):
#   ../data/cache, ../data/api.yml, ../game.yml, ../dev-settings.yml
# The cache, RSA key, xteas, saves, and real game.yml are NOT baked in —
# production mounts them over the paths below (see deploy/docker-compose.prod.yml).
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Start scripts + all jars (game-plugins rides in lib/ via the runtimeOnly dep)
COPY game-server/build/install/game-server/ /app/

# Content configs (cfg/, api.yml). Cache/rsa/xteas/saves are runtime mounts.
COPY data/ /app/data/

# Baked fallback configs; production mounts the real ones over these.
COPY game.example.yml /app/game.yml
COPY dev-settings.example.yml /app/dev-settings.yml

RUN chmod +x /app/bin/game-server && mkdir -p /app/data/saves /app/data/cache /app/data/rsa

WORKDIR /app/bin
EXPOSE 43594/tcp
ENTRYPOINT ["./game-server"]
