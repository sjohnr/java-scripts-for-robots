#!/usr/bin/env python3
"""noop: measures pure process-startup floor. No I/O, no argument-dependent
logic beyond recognizing --help."""
import sys


def main():
    for arg in sys.argv[1:]:
        if arg == "--help":
            sys.stdout.write("usage: noop\nprints \"ok\" and exits 0\n")
            return 0
    sys.stdout.write("ok\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
