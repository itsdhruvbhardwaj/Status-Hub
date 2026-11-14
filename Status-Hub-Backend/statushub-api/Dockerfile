FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/extractor
ARG YTDLP_VERSION
RUN /opt/extractor/bin/pip install --no-cache-dir "yt-dlp[default]${YTDLP_VERSION:+==$YTDLP_VERSION}"
ENV YTDLP_PATH=/opt/extractor/bin/yt-dlp
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
RUN mkdir media-cache && chown -R node:node /app
USER node
EXPOSE 3000
CMD ["node", "src/server.js"]
