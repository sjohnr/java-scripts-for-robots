import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
LOGSUM = os.path.join(HERE, "logsum")

SAMPLE = (
    "2024-01-01T10:00:00 INFO started\n"
    "2024-01-01T10:00:05 DEBUG cache warm\n"
    "2024-01-01T10:01:00 WARN slow query 120ms\n"
    "2024-01-01T10:02:00 ERROR connection to 10.0.0.1 refused\n"
    "2024-01-01T10:03:00 ERROR connection to 10.0.0.2 refused\n"
    "2024-01-01T10:04:00 ERROR request 3f2a9c1e failed\n"
    "2024-01-01T10:05:00 ERROR request 9b7d0e44 failed\n"
    "garbage line\n"
    "2024-01-01T10:06:00 info lowercase level\n"
    "2024-13-01T10:06:00 INFO bad month\n"
    "2024-01-01T10:07:00 ERROR request 7c1e2d9a failed\n"
)


def run(args, stdin=b""):
    return subprocess.run(
        [sys.executable, LOGSUM] + args,
        input=stdin,
        capture_output=True,
    )


class LogsumTests(unittest.TestCase):
    def summarize(self, text, args=()):
        with tempfile.NamedTemporaryFile("wb", delete=False) as handle:
            handle.write(text.encode("utf-8"))
            path = handle.name
        try:
            return run([path] + list(args))
        finally:
            os.unlink(path)

    def test_summary(self):
        result = self.summarize(SAMPLE)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")
        self.assertEqual(
            result.stdout.decode(),
            "total: 8\n"
            "by level: DEBUG=1 INFO=1 WARN=1 ERROR=5\n"
            "first: 2024-01-01T10:00:00\n"
            "last: 2024-01-01T10:07:00\n"
            "top errors:\n"
            "  3 request 3f2a9c1e failed\n"
            "  2 connection to 10.0.0.1 refused\n",
        )

    def test_top_defaults_to_five_and_limits(self):
        lines = "".join(
            f"2024-01-01T00:00:00 ERROR msg {chr(ord('a') + i)}\n" for i in range(7)
        )
        result = self.summarize(lines)
        self.assertEqual(len(result.stdout.decode().splitlines()), 5 + 5)
        result = self.summarize(lines, ["--top", "2"])
        self.assertEqual(
            result.stdout.decode().splitlines()[-2:],
            ["  1 msg a", "  1 msg b"],
        )

    def test_top_zero_still_prints_header(self):
        result = self.summarize(SAMPLE, ["--top", "0"])
        self.assertTrue(result.stdout.endswith(b"top errors:\n"))

    def test_no_errors_prints_empty_top_section(self):
        result = self.summarize("2024-01-01T00:00:00 INFO ok\n")
        self.assertEqual(
            result.stdout.decode(),
            "total: 1\n"
            "by level: DEBUG=0 INFO=1 WARN=0 ERROR=0\n"
            "first: 2024-01-01T00:00:00\n"
            "last: 2024-01-01T00:00:00\n"
            "top errors:\n",
        )

    def test_empty_input(self):
        result = self.summarize("")
        self.assertEqual(
            result.stdout.decode(),
            "total: 0\n"
            "by level: DEBUG=0 INFO=0 WARN=0 ERROR=0\n"
            "first: n/a\n"
            "last: n/a\n"
            "top errors:\n",
        )

    def test_stdin_and_dash(self):
        implicit = run([], stdin=SAMPLE.encode())
        explicit = run(["-"], stdin=SAMPLE.encode())
        self.assertEqual(implicit.returncode, 0)
        self.assertEqual(implicit.stdout, explicit.stdout)
        self.assertIn(b"total: 8\n", implicit.stdout)

    def test_since_filter(self):
        result = self.summarize(SAMPLE, ["--since", "2024-01-01T10:04:00"])
        self.assertIn(b"total: 3\n", result.stdout)
        self.assertIn(b"first: 2024-01-01T10:04:00\n", result.stdout)

    def test_level_filter(self):
        result = self.summarize(SAMPLE, ["--level", "ERROR"])
        self.assertEqual(
            result.stdout.decode(),
            "total: 5\n"
            "by level: DEBUG=0 INFO=0 WARN=0 ERROR=5\n"
            "first: 2024-01-01T10:02:00\n"
            "last: 2024-01-01T10:07:00\n"
            "top errors:\n"
            "  3 request 3f2a9c1e failed\n"
            "  2 connection to 10.0.0.1 refused\n",
        )

    def test_filters_combine_before_stats(self):
        result = self.summarize(SAMPLE, ["--level", "ERROR", "--since", "2024-01-01T10:05:00"])
        self.assertIn(b"total: 2\n", result.stdout)
        self.assertIn(b"top errors:\n  2 request 9b7d0e44 failed\n", result.stdout)

    def test_equals_form_and_last_flag_wins(self):
        result = self.summarize(SAMPLE, ["--level=WARN", "--level", "ERROR"])
        self.assertIn(b"total: 5\n", result.stdout)

    def test_normalization_order(self):
        text = (
            "2024-01-01T00:00:00 ERROR id 123e4567-e89b-12d3-a456-426614174000 x\n"
            "2024-01-01T00:00:01 ERROR id 123e4567-e89b-12d3-a456-426614174111 x\n"
            "2024-01-01T00:00:02 ERROR plain 123456 and deadbeef\n"
            "2024-01-01T00:00:03 ERROR plain 654321 and deadbeef\n"
        )
        result = self.summarize(text)
        self.assertIn(b"  2 id 123e4567-e89b-12d3-a456-426614174000 x\n", result.stdout)
        self.assertIn(b"  2 plain 123456 and deadbeef\n", result.stdout)

    def test_ties_sort_by_first_seen_message(self):
        text = (
            "2024-01-01T00:00:00 ERROR zeta\n"
            "2024-01-01T00:00:01 ERROR alpha\n"
        )
        result = self.summarize(text)
        self.assertTrue(result.stdout.endswith(b"top errors:\n  1 alpha\n  1 zeta\n"))

    def test_message_keeps_internal_spacing_and_drops_trailing_whitespace(self):
        result = self.summarize("2024-01-01T00:00:00 ERROR a  b   c   \r\n")
        self.assertIn(b"  1 a  b   c\n", result.stdout)
        for line in result.stdout.split(b"\n"):
            self.assertEqual(line, line.rstrip())

    def test_invalid_lines_are_skipped(self):
        text = (
            "2024-01-01T00:00:00 ERROR\n"
            "2024-01-01T00:00:00 TRACE nope\n"
            "2024-02-30T00:00:00 ERROR nope\n"
            "2024-01-01 ERROR nope\n"
        )
        result = self.summarize(text)
        self.assertIn(b"total: 0\n", result.stdout)

    def test_missing_file(self):
        result = run(["/nonexistent/path/logsum.log"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, b"error: cannot read /nonexistent/path/logsum.log\n")

    def test_invalid_since(self):
        result = run(["--since", "yesterday"], stdin=b"")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, b"error: invalid value for --since: yesterday\n")

    def test_invalid_level(self):
        result = run(["--level", "info"], stdin=b"")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, b"error: invalid value for --level: info\n")

    def test_unknown_flag(self):
        result = run(["--verbose"], stdin=b"")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, b"unknown flag: --verbose\n")

    def test_help(self):
        result = run(["--help"])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")
        for flag in (b"--since", b"--level", b"--top", b"--help", b"FILE"):
            self.assertIn(flag, result.stdout)

    def test_output_uses_lf_only(self):
        result = self.summarize(SAMPLE)
        self.assertNotIn(b"\r", result.stdout)
        self.assertTrue(result.stdout.endswith(b"\n"))


if __name__ == "__main__":
    unittest.main()
