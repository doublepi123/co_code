// Copied into the guest rootfs at /opt/cocode/android-shim.cjs and loaded via NODE_OPTIONS.
'use strict';

const os = require('os');
const fs = require('fs');
const { syncBuiltinESMExports } = require('module');

// On Android, SELinux makes uv_interface_addresses (behind os.networkInterfaces) fail with EACCES,
// which crashes T3 Code at startup. Fall back to a loopback-only interface list instead.
const original = os.networkInterfaces;
if (typeof original === 'function') {
  os.networkInterfaces = function networkInterfaces(...args) {
    try {
      return original.apply(this, args);
    } catch (_error) {
      return {
        lo: [
          {
            address: '127.0.0.1',
            netmask: '255.0.0.0',
            family: 'IPv4',
            mac: '00:00:00:00:00:00',
            internal: true,
            cidr: '127.0.0.1/8',
          },
        ],
      };
    }
  };
}

// Android forbids hard links in app storage, so PRoot runs with --link2symlink and fakes them with
// hidden `.l2s.*` files next to the original. Those fakes break the "write a temp file, link it into
// place, then remove the temp directory" pattern T3 Code uses for atomic first-writer-wins files:
// the directory removal fails with ENOTEMPTY and the server exits. A no-replace copy keeps the
// semantics callers rely on (the target appears with the full content, EEXIST when it already
// exists, ENOENT when the source is missing) without creating link artefacts.
const EXCL = fs.constants.COPYFILE_EXCL;
fs.link = function link(existingPath, newPath, callback) {
  return fs.copyFile(existingPath, newPath, EXCL, callback);
};
fs.linkSync = function linkSync(existingPath, newPath) {
  return fs.copyFileSync(existingPath, newPath, EXCL);
};
fs.promises.link = function link(existingPath, newPath) {
  return fs.promises.copyFile(existingPath, newPath, EXCL);
};
// ESM `import * as fs from "node:fs"` reads the builtin's live exports only after a sync.
syncBuiltinESMExports();
