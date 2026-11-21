FROM alpine:3.24.1 AS runtime

ARG COX_VERSION=0.2.22
ARG OMO_VERSION=5.1.79

RUN apk add --no-cache nodejs npm bash git curl ripgrep openssh-client ca-certificates python3 e2fsprogs \
    && apk add --no-cache --virtual .pty-build make g++ linux-headers nodejs-dev \
    && npm install --global --prefix /usr/local --no-fund --no-audit "@doublepi123/cox@$COX_VERSION" "omo-ai@$OMO_VERSION" \
    && cd /usr/local/lib/node_modules/@doublepi123/cox/node_modules/node-pty \
    && npx --yes node-gyp rebuild --nodedir=/usr \
    && test -s build/Release/pty.node \
    && rm -rf prebuilds \
    && apk del .pty-build \
    && rm -rf /root/.npm

RUN omo --version \
    && test "$(node -p 'require("/usr/local/lib/node_modules/omo-ai/package.json").version')" = "$OMO_VERSION"

COPY boot.sh /usr/local/sbin/cocode-boot
COPY start.sh /usr/local/sbin/cocode-start
COPY inittab /etc/inittab
RUN chmod 0755 /usr/local/sbin/cocode-boot /usr/local/sbin/cocode-start
CMD ["/usr/local/sbin/cocode-start"]

FROM alpine:3.24.1 AS pack
RUN apk add --no-cache erofs-utils
COPY --from=runtime / /rootfs/
RUN mkfs.erofs -zlz4hc,9 /cocode-linux.erofs /rootfs && fsck.erofs /cocode-linux.erofs

FROM scratch AS bundle
COPY --from=pack /cocode-linux.erofs /cocode-linux.erofs
