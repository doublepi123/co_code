#!/bin/sh
set -eu
umask 077

cocode_home=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export COX_HOME="$cocode_home/.cox"
export T3CODE_HOST=0.0.0.0
export T3CODE_PORT=3773
export T3CODE_NO_BROWSER=true

mkdir -p "$cocode_home/projects" "$COX_HOME/userdata"
password_file="$cocode_home/.cocode-password"
if [ ! -e "$password_file" ]; then
    pending=$(mktemp "$cocode_home/.cocode-password.XXXXXX")
    trap 'rm -f "$pending"' EXIT
    node -p 'require("node:crypto").randomBytes(24).toString("hex")' > "$pending"
    mv "$pending" "$password_file"
    trap - EXIT
fi
read -r COX_AUTH_PASSWORD < "$password_file"
export COX_AUTH_PASSWORD
if [ "${1:-}" = --console-password ]; then
    printf '\nCoCode local password: %s\n\n' "$COX_AUTH_PASSWORD" > /dev/console
fi

settings="$COX_HOME/userdata/settings.json"
if [ ! -e "$settings" ]; then
    printf '%s\n' '{"providerInstances":{"omo":{"driver":"omo","enabled":true,"config":{}}}}' > "$settings"
fi

cd "$cocode_home"
# The guest has just booted, so no Cox runs: a run record from the last boot is
# stale, and its pid may now belong to another process, which Cox would refuse.
rm -f "$COX_HOME/userdata/server-runtime.json"
# CoCode ships Cox in this image and updates it with the image.
export COX_UPDATES_MANAGED_BY=CoCode
exec env HOME="$cocode_home" /usr/local/bin/cox
