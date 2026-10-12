# mado-hub in a container. Build from this directory:
#   docker build -t mado-hub .
#   docker run -d --name mado-hub -p 7878:7878 -v mado-data:/data mado-hub
FROM rust:1-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked

FROM debian:bookworm-slim
COPY --from=build /src/target/release/mado-hub /usr/local/bin/mado-hub
# Config, projects and the painting cache live here; mount a volume to keep them.
ENV MADO_DATA=/data MADO_BIND=0.0.0.0:7878
VOLUME /data
# 7879 is only used when MADO_FRAME_BIND is set (a frame-only port).
EXPOSE 7878 7879
USER nobody
CMD ["mado-hub"]
