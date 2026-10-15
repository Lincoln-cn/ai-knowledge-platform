#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Probe an HTTP service, trying EVERY address family.

WHY THIS FILE EXISTS
--------------------
On this machine Vite binds only `::1` (IPv6). A probe against `127.0.0.1`
therefore gets WinError 10061 ("actively refused") while the dev server is
perfectly healthy -- and that reads as "the service is down".

The project already recorded this trap for `Test-NetConnection 127.0.0.1:5280`
(which is always false here). It applies to ANY 127.0.0.1-only probe --
including this repo's own gate runner, which shipped with such a probe once and
therefore could never see a running dev server.

=> never probe a single literal address on this machine. Try, in order:
     http://localhost:PORT   (resolver picks a family that works)
     http://[::1]:PORT       (what Vite actually binds)
     http://127.0.0.1:PORT   (what Spring Boot binds by default)

USAGE
-----
    python tools/probe_http.py 5280
    python tools/probe_http.py 18088 --path /actuator/health
    python tools/probe_http.py --url http://localhost:4205

EXIT
----
    0  some candidate answered with status < 500
    1  none answered
    2  bad usage
"""

import sys
import urllib.request


def candidates(port, path):
    return [
        "http://localhost:%d%s" % (port, path),
        "http://[::1]:%d%s" % (port, path),
        "http://127.0.0.1:%d%s" % (port, path),
    ]


def parse(argv):
    port = None
    path = "/"
    urls = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--path":
            i += 1
            path = argv[i] if i < len(argv) else "/"
        elif arg == "--url":
            i += 1
            if i < len(argv):
                urls.append(argv[i])
        elif arg.isdigit():
            port = int(arg)
        i += 1
    if port is not None:
        urls = candidates(port, path) + urls
    return urls


def main(argv):
    urls = parse(argv)
    if not urls:
        sys.stderr.write("usage: probe_http.py <port> [--path /x] | --url URL\n")
        return 2
    # Bypass any HTTP_PROXY/HTTPS_PROXY: a proxy answering "502" for a local
    # address would otherwise look like "the service answered" (HTTPError) --
    # i.e. a false reading in the one direction that matters (saying a service
    # is up when it is not).
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for url in urls:
        try:
            code = opener.open(url, timeout=3).status
        except Exception as exc:  # noqa: BLE001 - any failure means "try the next family"
            sys.stderr.write("  try %-42s -> %s\n" % (url, exc.__class__.__name__))
            continue
        sys.stdout.write("  OK  %-42s -> %d\n" % (url, code))
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
