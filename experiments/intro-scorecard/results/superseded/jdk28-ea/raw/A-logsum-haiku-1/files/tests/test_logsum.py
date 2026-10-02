import os
import subprocess
import sys
import tempfile
import unittest

LOGSUM = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "logsum")


def run(args, stdin=b""):
    return subprocess.run(
        [sys.executable, LOGSUM] + args,
        input=stdin,
        capture_output=True,
    )


class LogsumTest(unittest.TestCase):
    def assertSummary(self, data, args=(), stdout=None, status=0):
        result = run(list(args), stdin=data)
        self.assertEqual(result.stderr, b"")
        self.assertEqual(result.returncode, status)
        if stdout is not None:
            self.assertEqual(result.stdout.decode("utf-8"), stdout)
        return result

    def test_empty_input(self):
        self.assertSummary(
            b"",
            stdout=(
                "total: 0\n"
                "by level: DEBUG=0 INFO=0 WARN=0 ERROR=0\n"
                "first: n/a\n"
                "last: n/a\n"
                "top errors:\n"
            ),
        )

    def test_counts_first_last_and_top_errors(self):
        data = (
            b"2024-01-01T10:00:00 INFO started\n"
            b"2024-01-01T10:00:01 ERROR timeout after 30 seconds\n"
            b"2024-01-01T10:00:02 ERROR timeout after 45 seconds\n"
            b"2024-01-01T10:00:03 WARN slow\n"
            b"2024-01-01T10:00:04 ERROR disk full\n"
            b"2024-01-01T10:00:05 DEBUG tick\n"
        )
        self.assertSummary(
            data,
            stdout=(
                "total: 6\n"
                "by level: DEBUG=1 INFO=1 WARN=1 ERROR=3\n"
                "first: 2024-01-01T10:00:00\n"
                "last: 2024-01-01T10:00:05\n"
                "top errors:\n"
                "  2 timeout after 30 seconds\n"
                "  1 disk full\n"
            ),
        )

    def test_top_default_and_override(self):
        words = ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf"]
        lines = b"".join(
            b"2024-01-01T00:00:00 ERROR %s\n" % word.encode() for word in words
        )
        out = run([], stdin=lines).stdout.decode()
        self.assertEqual(out.count("\n  "), 5)
        out = run(["--top", "2"], stdin=lines).stdout.decode()
        self.assertEqual(out.count("\n  "), 2)
        out = run(["--top=0"], stdin=lines).stdout.decode()
        self.assertTrue(out.endswith("top errors:\n"))

    def test_error_ties_sorted_by_message(self):
        data = (
            b"2024-01-01T00:00:00 ERROR banana\n"
            b"2024-01-01T00:00:01 ERROR apple\n"
        )
        out = run([], stdin=data).stdout.decode()
        self.assertTrue(out.endswith("  1 apple\n  1 banana\n"))

    def test_normalization_order(self):
        data = (
            b"2024-01-01T00:00:00 ERROR req 123e4567-e89b-12d3-a456-426614174000 failed\n"
            b"2024-01-01T00:00:01 ERROR req 00000000-0000-0000-0000-000000000001 failed\n"
            b"2024-01-01T00:00:02 ERROR blob deadbeef12 gone\n"
            b"2024-01-01T00:00:03 ERROR blob cafe1234 gone\n"
            b"2024-01-01T00:00:04 ERROR retry 123456 later\n"
            b"2024-01-01T00:00:05 ERROR retry 789012 later\n"
        )
        out = run([], stdin=data).stdout.decode()
        self.assertIn("  2 req 123e4567-e89b-12d3-a456-426614174000 failed\n", out)
        self.assertIn("  2 blob deadbeef12 gone\n", out)
        self.assertIn("  2 retry 123456 later\n", out)

    def test_hex_rule_requires_a_letter_and_word_boundaries(self):
        # Digits-only runs skip the hex rule and fall to <num>, so 123456 and
        # 654321 group together. abcdef and fedcba are hex (contain a letter)
        # and group together. deadbeefzz has no word boundary after the hex run,
        # so it is left alone and stays its own group.
        data = (
            b"2024-01-01T00:00:00 ERROR code 123456 x\n"
            b"2024-01-01T00:00:01 ERROR code 654321 x\n"
            b"2024-01-01T00:00:02 ERROR code abcdef x\n"
            b"2024-01-01T00:00:03 ERROR code fedcba x\n"
            b"2024-01-01T00:00:04 ERROR code deadbeefzz x\n"
        )
        out = run([], stdin=data).stdout.decode()
        self.assertIn("  2 code 123456 x\n", out)
        self.assertIn("  2 code abcdef x\n", out)
        self.assertIn("  1 code deadbeefzz x\n", out)

    def test_filters_apply_before_statistics(self):
        data = (
            b"2024-01-01T00:00:00 INFO a\n"
            b"2024-01-02T00:00:00 ERROR b\n"
            b"2024-01-03T00:00:00 ERROR c\n"
        )
        self.assertSummary(
            data,
            args=["--since", "2024-01-02T00:00:00", "--level", "ERROR"],
            stdout=(
                "total: 2\n"
                "by level: DEBUG=0 INFO=0 WARN=0 ERROR=2\n"
                "first: 2024-01-02T00:00:00\n"
                "last: 2024-01-03T00:00:00\n"
                "top errors:\n"
                "  1 b\n"
                "  1 c\n"
            ),
        )

    def test_malformed_lines_are_skipped(self):
        data = (
            b"2024-01-01T00:00:00 INFO ok\n"
            b"2024-01-01T00:00:00 INFO\n"
            b"2024-01-01T00:00:00 INFO   \n"
            b"2024-13-01T00:00:00 INFO bad date\n"
            b"2024-01-01 00:00:00 INFO bad sep\n"
            b"2024-01-01T00:00:00 info lower\n"
            b"2024-01-01T00:00:00\tINFO\ttabs\n"
            b" 2024-01-01T00:00:00 INFO leading\n"
            b"\n"
        )
        self.assertSummary(
            data,
            stdout=(
                "total: 1\n"
                "by level: DEBUG=0 INFO=1 WARN=0 ERROR=0\n"
                "first: 2024-01-01T00:00:00\n"
                "last: 2024-01-01T00:00:00\n"
                "top errors:\n"
            ),
        )

    def test_message_keeps_internal_spacing_and_strips_trailing_space(self):
        data = b"2024-01-01T00:00:00 ERROR a  b   c   \r\n"
        out = run([], stdin=data).stdout.decode()
        self.assertIn("  1 a  b   c\n", out)

    def test_crlf_input_and_utf8_output(self):
        data = "2024-01-01T00:00:00 ERROR café failed\r\n".encode("utf-8")
        out = run([], stdin=data).stdout
        self.assertIn("  1 café failed\n".encode("utf-8"), out)
        self.assertNotIn(b"\r", out)

    def test_invalid_utf8_does_not_crash(self):
        data = b"2024-01-01T00:00:00 ERROR bad \xff byte\n"
        result = run([], stdin=data)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")

    def test_reads_file_and_dash_as_stdin(self):
        data = b"2024-01-01T00:00:00 INFO hi\n"
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            handle.write(data)
            path = handle.name
        try:
            from_file = run([path]).stdout
            from_dash = run(["-"], stdin=data).stdout
            from_stdin = run([], stdin=data).stdout
        finally:
            os.unlink(path)
        self.assertEqual(from_file, from_dash)
        self.assertEqual(from_dash, from_stdin)

    def test_missing_file(self):
        result = run(["/no/such/file.log"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(
            result.stderr, b"error: cannot read /no/such/file.log\n"
        )

    def test_unknown_flag(self):
        result = run(["--bogus"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, b"unknown flag: --bogus\n")

    def test_invalid_since(self):
        result = run(["--since", "yesterday"], stdin=b"")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(
            result.stderr, b"error: invalid value for --since: yesterday\n"
        )

    def test_invalid_level(self):
        result = run(["--level", "info"], stdin=b"")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(
            result.stderr, b"error: invalid value for --level: info\n"
        )

    def test_help_lists_every_flag(self):
        result = run(["--help"])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")
        text = result.stdout.decode()
        for token in ("FILE", "--since", "--level", "--top"):
            self.assertIn(token, text)
        for line in text.split("\n"):
            self.assertEqual(line, line.rstrip())


if __name__ == "__main__":
    unittest.main()
