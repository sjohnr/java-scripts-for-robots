#!/usr/bin/env python3
import sys


def main() -> None:
    assert len(sys.argv) == 2, "No message provided"

    expected = "Hello"
    actual = sys.argv[1]
    try:
        assert expected == actual, f"Failed: expected {expected!r}, actual {actual!r}"
        print("Passed")
    except AssertionError:
        print("Failed")


if __name__ == "__main__":
    main()
