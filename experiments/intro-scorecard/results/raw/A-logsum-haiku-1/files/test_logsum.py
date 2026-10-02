"""Tests for the logsum CLI, run against the script as a black box."""

import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
LOGSUM = os.path.join(HERE, "logsum")

SAMPLE = """\
2026-01-01T10:00:00 INFO service started
2026-01-01T10:00:01 DEBUG cache warm
2026-01-01T10:00:02 ERROR db timeout after 30 seconds
2026-01-01T10:00:03 ERROR db timeout after 45 seconds
2026-01-01T10:00:04 WARN disk at 91%
2026-01-01T10:00:05 ERROR request 3f2a9c1b failed
2026-01-01T10:00:06 ERROR request 77d01e4a failed
this line is garbage
2026-13-01T10:00:07 ERROR bad month timestamp
2026-01-01T10:00:08 info lowercase level
2026-01-01T10:00:09 ERROR db timeout after 60 seconds
"""


def run(args=(), stdin=b""):
    return subprocess.run(
        [sys.executable, LOGSUM, *args],
        input=stdin,
        capture_output=True,
    )


def write_file(directory, text):
    path = os.path.join(directory, "log.txt")
    with open(path, "wb") as handle:
        handle.write(text.encode("utf-8"))
    return path


class LogsumTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = write_file(self.tmp.name, SAMPLE)

    def assert_success(self, result, expected_stdout):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, b"")
        self.assertEqual(result.stdout.decode("utf-8"), expected_stdout)

    def test_summary_from_file(self):
        result = run([self.path])
        self.assert_success(
            result,
            "total: 8\n"
            "by level: DEBUG=1 INFO=1 WARN=1 ERROR=5\n"
            "first: 2026-01-01T10:00:00\n"
            "last: 2026-01-01T10:00:09\n"
            "top errors:\n"
            "  3 db timeout after 30 seconds\n"
            "  2 request 3f2a9c1b failed\n",
        )

    def test_stdin_matches_file(self):
        from_file = run([self.path])
        from_stdin = run(stdin=SAMPLE.encode("utf-8"))
        self.assertEqual(from_file.stdout, from_stdin.stdout)
        self.assertEqual(from_stdin.returncode, 0)

    def test_dash_reads_stdin(self):
        result = run(["-"], stdin=SAMPLE.encode("utf-8"))
        self.assertEqual(result.returncode, 0)
        self.assertTrue(result.stdout.startswith(b"total: 8\n"))

    def test_top_defaults_to_five_and_limits_groups(self):
        lines = "".join(
            f"2026-01-01T00:00:00 ERROR unique failure {chr(ord('a') + i)}\n"
            for i in range(8)
        )
        result = run(stdin=lines.encode("utf-8"))
        self.assertEqual(result.stdout.count(b"\n  1 unique failure"), 5)

    def test_top_option_and_zero(self):
        result = run(["--top", "1", self.path])
        self.assertIn(b"top errors:\n  3 db timeout after 30 seconds\n", result.stdout)
        self.assertEqual(result.stdout.count(b"\n  "), 1)

        result = run(["--top=0", self.path])
        self.assertTrue(result.stdout.endswith(b"top errors:\n"))

    def test_no_errors_prints_empty_top_errors_heading(self):
        result = run(stdin=b"2026-01-01T00:00:00 INFO ok\n")
        self.assert_success(
            result,
            "total: 1\n"
            "by level: DEBUG=0 INFO=1 WARN=0 ERROR=0\n"
            "first: 2026-01-01T00:00:00\n"
            "last: 2026-01-01T00:00:00\n"
            "top errors:\n",
        )

    def test_empty_input_reports_n_a(self):
        result = run(stdin=b"")
        self.assert_success(
            result,
            "total: 0\n"
            "by level: DEBUG=0 INFO=0 WARN=0 ERROR=0\n"
            "first: n/a\n"
            "last: n/a\n"
            "top errors:\n",
        )

    def test_since_filter(self):
        result = run(["--since", "2026-01-01T10:00:04", self.path])
        self.assertIn(b"total: 4\n", result.stdout)
        self.assertIn(b"first: 2026-01-01T10:00:04\n", result.stdout)

    def test_level_filter(self):
        result = run(["--level", "ERROR", self.path])
        self.assertIn(b"total: 5\n", result.stdout)
        self.assertIn(b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=5\n", result.stdout)

    def test_error_ties_sorted_by_message(self):
        text = (
            "2026-01-01T00:00:00 ERROR zeta\n"
            "2026-01-01T00:00:01 ERROR alpha\n"
        )
        result = run(stdin=text.encode("utf-8"))
        self.assertTrue(result.stdout.endswith(b"top errors:\n  1 alpha\n  1 zeta\n"))

    def test_normalization_order(self):
        text = (
            "2026-01-01T00:00:00 ERROR id 123e4567-e89b-12d3-a456-426614174000\n"
            "2026-01-01T00:00:01 ERROR id 223e4567-e89b-12d3-a456-426614174000\n"
            "2026-01-01T00:00:02 ERROR id 0badc0de in 42 ms\n"
            "2026-01-01T00:00:03 ERROR id 1badc0de in 43 ms\n"
            "2026-01-01T00:00:04 ERROR id 123456 plain number\n"
        )
        result = run(stdin=text.encode("utf-8"))
        self.assertTrue(
            result.stdout.endswith(
                b"top errors:\n"
                b"  2 id 0badc0de in 42 ms\n"
                b"  2 id 123e4567-e89b-12d3-a456-426614174000\n"
                b"  1 id 123456 plain number\n"
            ),
            result.stdout,
        )

    def test_same_group_displays_first_seen_message(self):
        text = (
            "2026-01-01T00:00:00 ERROR job 1 failed\n"
            "2026-01-01T00:00:01 ERROR job 2 failed\n"
        )
        result = run(stdin=text.encode("utf-8"))
        self.assertTrue(result.stdout.endswith(b"  2 job 1 failed\n"))

    def test_skipped_lines_do_not_count(self):
        text = (
            "2026-01-01T00:00:00 INFO\n"
            "2026-01-01T00:00:00 INFO   \n"
            "2026-01-01T00:00:00INFO msg\n"
            "2026-01-01 INFO msg\n"
        )
        result = run(stdin=text.encode("utf-8"))
        self.assert_success(
            result,
            "total: 0\n"
            "by level: DEBUG=0 INFO=0 WARN=0 ERROR=0\n"
            "first: n/a\n"
            "last: n/a\n"
            "top errors:\n",
        )

    def test_message_keeps_internal_spacing_and_crlf_is_stripped(self):
        text = "2026-01-01T00:00:00 ERROR a  b\r\n"
        result = run(stdin=text.encode("utf-8"))
        self.assertTrue(result.stdout.endswith(b"  1 a  b\n"))

    def test_output_has_no_trailing_whitespace(self):
        text = "2026-01-01T00:00:00 ERROR boom   \n"
        result = run(stdin=text.encode("utf-8"))
        for line in result.stdout.split(b"\n"):
            self.assertEqual(line, line.rstrip())

    def test_help_exits_zero_and_lists_flags(self):
        result = run(["--help"])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")
        for flag in ("FILE", "--since", "--level", "--top"):
            self.assertIn(flag.encode(), result.stdout)

    def test_unknown_flag(self):
        result = run(["--bogus", self.path])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, b"unknown flag: --bogus\n")

    def test_invalid_since(self):
        result = run(["--since", "yesterday", self.path])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, b"error: invalid value for --since: yesterday\n")

    def test_invalid_level(self):
        result = run(["--level", "error", self.path])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, b"error: invalid value for --level: error\n")

    def test_missing_file(self):
        missing = os.path.join(self.tmp.name, "nope.log")
        result = run([missing])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, f"error: cannot read {missing}\n".encode())

    def test_directory_is_unreadable_input(self):
        result = run([self.tmp.name])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")


if __name__ == "__main__":
    unittest.main()
