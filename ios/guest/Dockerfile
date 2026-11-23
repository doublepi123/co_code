FROM alpine:3.24.1 AS runtime

ARG COX_VERSION=0.2.32
ARG OMO_VERSION=5.1.81

RUN apk add --no-cache nodejs npm bash git curl ripgrep openssh-client ca-certificates python3 e2fsprogs \
    && apk add --no-cache --virtual .pty-build make g++ linux-headers nodejs-dev

COPY manager.mjs /usr/lib/cocode/manager.mjs
COPY boot.sh /usr/local/sbin/cocode-boot
COPY inittab /etc/inittab
RUN chmod 0755 /usr/local/sbin/cocode-boot \
    && node /usr/lib/cocode/manager.mjs install-seed cox "$COX_VERSION" "/usr/lib/cocode/seed/cox/$COX_VERSION" \
    && node /usr/lib/cocode/manager.mjs install-seed omo "$OMO_VERSION" "/usr/lib/cocode/seed/omo/$OMO_VERSION" \
    && rm -rf /root/.npm

CMD ["node", "/usr/lib/cocode/manager.mjs", "serve"]

FROM alpine:3.24.1 AS pack
RUN apk add --no-cache erofs-utils
COPY --from=runtime / /rootfs/
RUN mkfs.erofs -zlz4hc,9 /cocode-linux.erofs /rootfs && fsck.erofs /cocode-linux.erofs

FROM scratch AS bundle
COPY --from=pack /cocode-linux.erofs /cocode-linux.erofs
