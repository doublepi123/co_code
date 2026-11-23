#!/bin/sh
set -eu

mkdir -p /dev/pts /dev/shm
mount -t devpts -o mode=0620 devpts /dev/pts
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /dev/shm
ip link set lo up
ip link set eth0 up
udhcpc -n -q -i eth0

exec node /usr/lib/cocode/manager.mjs serve
