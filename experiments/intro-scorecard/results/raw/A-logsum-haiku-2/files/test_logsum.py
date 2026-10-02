import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
LOGSUM = os.path.join(HERE, "logsum")


def run(*args, stdin=b""):
    return subprocess.run([sys.executable, LOGSUM, *args], input=stdin, capture_output=True)


class LogsumTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)

    def write_log(self, text, name="app.log"):
        path = os.path.join(self._tmp.name, name)
        with open(path, "wb") as f:
            f.write(text.encode("utf-8"))
        return path

    def assert_result(self, result, stdout, code=0, stderr=b""):
        self.assertEqual(result.returncode, code, result.stderr)
        self.assertEqual(result.stdout, stdout)
        self.assertEqual(result.stderr, stderr)

    def test_summary(self):
        log = self.write_log(
            "2024-01-01T10:00:00 INFO started\n"
            "2024-01-01T10:00:01 DEBUG details here\n"
            "2024-01-01T10:00:02 WARN disk 91% full\n"
            "2024-01-01T10:00:03 ERROR connection to 10.0.0.5 failed\n"
            "2024-01-01T10:00:04 ERROR connection to 10.0.0.9 failed\n"
        )
        self.assert_result(
            run(log),
            b"total: 5\n"
            b"by level: DEBUG=1 INFO=1 WARN=1 ERROR=2\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T10:00:04\n"
            b"top errors:\n"
            b"  2 connection to 10.0.0.5 failed\n",
        )

    def test_malformed_lines_are_skipped(self):
        log = self.write_log(
            "garbage\n"
            "2024-01-01T10:00:00 INFO\n"
            "2024-01-01T10:00:00 INFO   \n"
            "2024-13-01T00:00:00 INFO bad month\n"
            "2024-02-30T00:00:00 INFO bad day\n"
            "2024-1-01T10:00:00 INFO short month\n"
            "2024-01-01 10:00:00 INFO space separator\n"
            "2024-01-01T10:00:00 info lowercase level\n"
            "2024-01-01T10:00:00 TRACE unknown level\n"
            "  2024-01-01T10:00:00 INFO leading space\n"
            "2024-01-01T10:00:00 INFO ok\n"
        )
        self.assert_result(
            run(log),
            b"total: 1\n"
            b"by level: DEBUG=0 INFO=1 WARN=0 ERROR=0\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T10:00:00\n"
            b"top errors:\n",
        )

    def test_empty_input(self):
        self.assert_result(
            run(self.write_log("")),
            b"total: 0\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=0\n"
            b"first: n/a\n"
            b"last: n/a\n"
            b"top errors:\n",
        )

    def test_since_and_level_filters_apply_before_stats(self):
        log = self.write_log(
            "2024-01-01T09:00:00 ERROR early boom\n"
            "2024-01-01T10:00:00 INFO a\n"
            "2024-01-01T11:00:00 ERROR late boom\n"
            "2024-01-01T12:00:00 WARN b\n"
        )
        self.assert_result(
            run(log, "--since", "2024-01-01T10:00:00"),
            b"total: 3\n"
            b"by level: DEBUG=0 INFO=1 WARN=1 ERROR=1\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T12:00:00\n"
            b"top errors:\n"
            b"  1 late boom\n",
        )
        self.assert_result(
            run(log, "--level=ERROR"),
            b"total: 2\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=2\n"
            b"first: 2024-01-01T09:00:00\n"
            b"last: 2024-01-01T11:00:00\n"
            b"top errors:\n"
            b"  1 early boom\n"
            b"  1 late boom\n",
        )

    def test_message_is_verbatim_but_trailing_whitespace_is_dropped(self):
        log = self.write_log("2024-01-01T10:00:00 ERROR a  b\tc   \n")
        self.assert_result(
            run(log),
            b"total: 1\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=1\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T10:00:00\n"
            b"top errors:\n"
            b"  1 a  b\tc\n",
        )

    def test_normalization_groups_messages(self):
        log = self.write_log(
            "2024-01-01T00:00:00 ERROR id 123e4567-e89b-12d3-a456-426614174000 missing\n"
            "2024-01-01T00:00:01 ERROR id 9f8e7d6c-1111-2222-3333-444455556666 missing\n"
            "2024-01-01T00:00:02 ERROR cache deadbeef miss\n"
            "2024-01-01T00:00:03 ERROR cache cafe42 miss\n"
            "2024-01-01T00:00:04 ERROR retry 123456 times\n"
            "2024-01-01T00:00:05 ERROR retry 7 times\n"
        )
        self.assert_result(
            run(log),
            b"total: 6\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=6\n"
            b"first: 2024-01-01T00:00:00\n"
            b"last: 2024-01-01T00:00:05\n"
            b"top errors:\n"
            b"  2 cache deadbeef miss\n"
            b"  2 id 123e4567-e89b-12d3-a456-426614174000 missing\n"
            b"  2 retry 123456 times\n",
        )

    def test_hex_rule_needs_a_letter_and_six_digits(self):
        log = self.write_log(
            "2024-01-01T00:00:00 ERROR code 1234567\n"
            "2024-01-01T00:00:01 ERROR code abcde\n"
        )
        self.assert_result(
            run(log, "--top", "10"),
            b"total: 2\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=2\n"
            b"first: 2024-01-01T00:00:00\n"
            b"last: 2024-01-01T00:00:01\n"
            b"top errors:\n"
            b"  1 code 1234567\n"
            b"  1 code abcde\n",
        )

    def test_top_limits_and_zero(self):
        log = self.write_log(
            "2024-01-01T00:00:00 ERROR c\n"
            "2024-01-01T00:00:01 ERROR b\n"
            "2024-01-01T00:00:02 ERROR a\n"
            "2024-01-01T00:00:03 ERROR a\n"
        )
        self.assert_result(
            run(log, "--top", "2"),
            b"total: 4\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=4\n"
            b"first: 2024-01-01T00:00:00\n"
            b"last: 2024-01-01T00:00:03\n"
            b"top errors:\n"
            b"  2 a\n"
            b"  1 b\n",
        )
        self.assert_result(
            run(log, "--top", "0"),
            b"total: 4\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=4\n"
            b"first: 2024-01-01T00:00:00\n"
            b"last: 2024-01-01T00:00:03\n"
            b"top errors:\n",
        )

    def test_crlf_input(self):
        log = self.write_log("2024-01-01T10:00:00 ERROR boom\r\n2024-01-01T10:00:01 INFO ok\r\n")
        self.assert_result(
            run(log),
            b"total: 2\n"
            b"by level: DEBUG=0 INFO=1 WARN=0 ERROR=1\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T10:00:01\n"
            b"top errors:\n"
            b"  1 boom\n",
        )

    def test_utf8_messages(self):
        log = self.write_log("2024-01-01T10:00:00 ERROR café ☃ failed\n")
        self.assert_result(
            run(log),
            b"total: 1\n"
            b"by level: DEBUG=0 INFO=0 WARN=0 ERROR=1\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T10:00:00\n"
            b"top errors:\n"
            b"  1 caf\xc3\xa9 \xe2\x98\x83 failed\n",
        )

    def test_stdin_default_and_dash(self):
        text = b"2024-01-01T10:00:00 WARN from stdin\n"
        expected = (
            b"total: 1\n"
            b"by level: DEBUG=0 INFO=0 WARN=1 ERROR=0\n"
            b"first: 2024-01-01T10:00:00\n"
            b"last: 2024-01-01T10:00:00\n"
            b"top errors:\n"
        )
        self.assert_result(run(stdin=text), expected)
        self.assert_result(run("-", stdin=text), expected)

    def test_help_lists_every_flag(self):
        result = run("--help")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")
        for flag in (b"FILE", b"--since", b"--level", b"--top", b"--help"):
            self.assertIn(flag, result.stdout)

    def test_unknown_flag(self):
        self.assert_result(run("--bogus"), b"", code=2, stderr=b"unknown flag: --bogus\n")
        self.assert_result(run("--bogus=1"), b"", code=2, stderr=b"unknown flag: --bogus\n")

    def test_bad_since(self):
        self.assert_result(
            run("--since", "yesterday", stdin=b""),
            b"",
            code=2,
            stderr=b"error: invalid value for --since: yesterday\n",
        )

    def test_bad_level(self):
        self.assert_result(
            run("--level", "info", stdin=b""),
            b"",
            code=2,
            stderr=b"error: invalid value for --level: info\n",
        )

    def test_bad_top(self):
        self.assert_result(
            run("--top", "-3", stdin=b""),
            b"",
            code=2,
            stderr=b"error: invalid value for --top: -3\n",
        )

    def test_missing_value(self):
        self.assert_result(run("--since"), b"", code=2, stderr=b"error: missing value for --since\n")

    def test_extra_positional(self):
        log = self.write_log("")
        self.assert_result(run(log, log), b"", code=2, stderr=f"unexpected argument: {log}\n".encode())

    def test_missing_file(self):
        missing = os.path.join(self._tmp.name, "nope.log")
        self.assert_result(
            run(missing),
            b"",
            code=2,
            stderr=f"error: cannot read {missing}\n".encode(),
        )


if __name__ == "__main__":
    unittest.main()
