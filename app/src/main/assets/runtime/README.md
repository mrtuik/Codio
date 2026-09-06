# Runtime package contract

`runtime-manifest.json` is intentionally checked into the app as configuration
instead of embedding a large Linux rootfs in the source bundle. Before shipping,
replace each architecture's URL and SHA-256 with the corresponding release
asset hosted by the Rlaude distribution service.

Each archive must be a gzip-compressed tarball containing a self-contained
rootfs with at least:

```text
/bin/sh
/usr/bin/node
/usr/bin/npm
```

The archive is extracted as data. It is never executed directly by Android.
`libproot.so` is the only bootstrap/runtime executable and must be supplied as
an install-time native library under `app/src/main/jniLibs/<abi>/`.