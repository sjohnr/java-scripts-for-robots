import os
import subprocess
import sys
import unittest

CLI = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "jsonpick")


def run(args, stdin=b""):
    proc = subprocess.run([sys.executable, CLI] + args, input=stdin,
                          capture_output=True)
    return proc.returncode, proc.stdout, proc.stderr


DOC = (b'{"a": {"b": [{"c": "hi"}, 2]}, "z": 1.50, "n": null, "t": true, '
       b'"u": "caf\\u00e9 \\ud83d\\ude00", "e": [], "o": {}, "k": {"y": 1, "x": [1, [2]]}}')


class DefaultMode(unittest.TestCase):
    def test_string_printed_raw(self):
        self.assertEqual(run(["a.b[0].c"], DOC), (0, b"hi\n", b""))

    def test_number_canonical(self):
        self.assertEqual(run(["z"], DOC), (0, b"1.5\n", b""))

    def test_null_and_bool(self):
        self.assertEqual(run(["n"], DOC), (0, b"null\n", b""))
        self.assertEqual(run(["t"], DOC), (0, b"true\n", b""))

    def test_number_forms(self):
        for lit, out in [(b"1.0", b"1"), (b"-0.0", b"0"), (b"100", b"100"),
                         (b"1e2", b"100"), (b"1.25e-3", b"0.00125"),
                         (b"12345678901234567890", b"12345678901234567890")]:
            self.assertEqual(run(["v"], b'{"v": ' + lit + b"}"), (0, out + b"\n", b""), lit)

    def test_object_compact_key_order(self):
        self.assertEqual(run(["k"], DOC), (0, b'{"y":1,"x":[1,[2]]}\n', b""))

    def test_empty_containers(self):
        self.assertEqual(run(["e"], DOC), (0, b"[]\n", b""))
        self.assertEqual(run(["o"], DOC), (0, b"{}\n", b""))

    def test_unicode_utf8_output(self):
        self.assertEqual(run(["u"], DOC), (0, "café 😀\n".encode("utf-8"), b""))

    def test_root_forms(self):
        for p in [".", ""]:
            code, out, _ = run([p], b"[1, 2]")
            self.assertEqual((code, out), (0, b"[1,2]\n"))

    def test_leading_dot_and_index_at_root(self):
        self.assertEqual(run([".a.b[0].c"], DOC), (0, b"hi\n", b""))
        self.assertEqual(run(["[1]"], b"[5, 6]"), (0, b"6\n", b""))

    def test_nested_index(self):
        self.assertEqual(run(["k.x[1][0]"], DOC), (0, b"2\n", b""))

    def test_not_found(self):
        for p in ["missing", "a.b[5]", "a.b.c", "z[0]", "a[0]", "[0]"]:
            code, out, err = run([p], DOC)
            self.assertEqual((code, out), (1, b""), p)
            self.assertEqual(err, f"error: path not found: {p}\n".encode(), p)

    def test_invalid_path_is_usage_error(self):
        for p in ["a..b", "a.", "a[", "a[x]", "a[]", "a[0]b", "..a", "a[-1]"]:
            code, out, _ = run([p], DOC)
            self.assertEqual((code, out), (2, b""), p)

    def test_stdin_and_file_argument(self):
        self.assertEqual(run(["a", "-"], b'{"a": 1}'), (0, b"1\n", b""))
        path = os.path.join(os.path.dirname(CLI), "tests", "_tmp_doc.json")
        with open(path, "wb") as f:
            f.write(b'{"a": "file"}')
        try:
            self.assertEqual(run(["a", path]), (0, b"file\n", b""))
        finally:
            os.remove(path)

    def test_missing_file_is_usage_error(self):
        code, out, err = run(["a", "/nonexistent/jsonpick-test.json"])
        self.assertEqual((code, out), (2, b""))
        self.assertTrue(err.startswith(b"error: cannot read"))


class Errors(unittest.TestCase):
    def test_invalid_json_names_position(self):
        code, out, err = run(["a"], b'{"a":\n  }')
        self.assertEqual((code, out), (2, b""))
        self.assertTrue(err.startswith(b"error: invalid JSON: "))
        self.assertIn(b"line 2", err)

    def test_invalid_json_variants(self):
        for bad in [b"", b"{", b"[1,]", b"NaN", b"Infinity", b"01", b'{"a":1} x',
                    b'"\\ud800"', b'"\\udc00"', b'"a\x01"', b"\xff", b"\xef\xbb\xbf{}"]:
            code, out, err = run(["a"], bad)
            self.assertEqual((code, out), (2, b""), bad)
            self.assertTrue(err.startswith(b"error: invalid JSON: "), bad)

    def test_usage_errors(self):
        for args in [[], ["a", "b", "c"], ["a", "--depth"], ["a", "--depth", "-1"],
                     ["a", "--depth=x"], ["a", "--bogus"], ["a", "-x"]]:
            code, out, _ = run(args, DOC)
            self.assertEqual((code, out), (2, b""), args)

    def test_unknown_flag_message(self):
        code, out, err = run(["a", "--bogus"], DOC)
        self.assertEqual(err, b"unknown flag: --bogus\n")

    def test_help(self):
        code, out, err = run(["--help"])
        self.assertEqual(code, 0)
        self.assertEqual(err, b"")
        for flag in [b"PATH", b"FILE", b"--shape", b"--depth"]:
            self.assertIn(flag, out)


class ShapeMode(unittest.TestCase):
    def shape(self, path, doc, depth=None):
        args = [path, "--shape"] + (["--depth", str(depth)] if depth is not None else [])
        return run(args, doc)

    def test_default_depth_two(self):
        code, out, err = self.shape(".", DOC)
        self.assertEqual((code, err), (0, b""))
        self.assertEqual(out, b"object{a: object{b: array[2]}, z: number, "
                              b"n: null, t: boolean, u: string, e: array[0], o: object{}, "
                              b"k: object{y: number, x: array[2]}}\n")

    def test_depth_zero(self):
        self.assertEqual(self.shape(".", DOC, 0)[1], b"object{8 keys}\n")
        self.assertEqual(self.shape("a.b", DOC, 0)[1], b"array[2]\n")
        self.assertEqual(self.shape("z", DOC, 0)[1], b"number\n")

    def test_depth_one(self):
        self.assertEqual(self.shape("a.b", DOC, 1)[1], b"array[2] of object{1 keys}\n")
        self.assertEqual(self.shape("k", DOC, 1)[1], b"object{y: number, x: array[2]}\n")
        self.assertEqual(self.shape("k", DOC, 2)[1], b"object{y: number, x: array[2] of number}\n")
        self.assertEqual(self.shape("k.x", DOC, 2)[1], b"array[2] of number\n")
        self.assertEqual(self.shape("k.x", DOC, 3)[1], b"array[2] of number\n")
        doc = b'[[[1]]]'
        self.assertEqual(self.shape(".", doc, 2)[1], b"array[1] of array[1] of array[1]\n")

    def test_scalars_and_subtree(self):
        self.assertEqual(self.shape("a.b[0].c", DOC)[1], b"string\n")
        self.assertEqual(self.shape("t", DOC, 5)[1], b"boolean\n")

    def test_not_found_and_invalid_json_under_shape(self):
        self.assertEqual(self.shape("nope", DOC)[0], 1)
        self.assertEqual(self.shape(".", b"{")[0], 2)


if __name__ == "__main__":
    unittest.main()
