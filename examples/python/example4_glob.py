#!/usr/bin/env python3
import argparse
import glob
import os


def main() -> None:
    parser = argparse.ArgumentParser(description="List files matching a glob pattern.")
    parser.add_argument("pattern", help="glob pattern to match (e.g. 'json/*.json', '~/.m2/repository/**/*.jar')")
    args = parser.parse_args()

    pattern = os.path.expanduser(args.pattern)
    for file in sorted(glob.iglob(pattern, recursive=True, include_hidden=True)):
        if os.path.isfile(file):
            print(file)


if __name__ == "__main__":
    main()
