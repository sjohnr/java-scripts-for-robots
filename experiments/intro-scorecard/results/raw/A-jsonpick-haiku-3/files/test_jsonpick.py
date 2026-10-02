import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jsonpick")


def run(args, stdin=b""):
    proc = subprocess.run(
        [sys.executable, SCRIPT] + args,
        input=stdin,
        capture_output=True,
    )
    return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")


DOC = b'{"a": {"b": [{"c": 1}, [10, 20]], "s": "hi"}, "z": 2.50, "t": true, "n": null}'


class ValuePrintTests(unittest.TestCase):
    def test_string_printed_raw(self):
        self.assertEqual(run(["a.s"], DOC), (0, "hi\n", ""))

    def test_integer_and_float_canonical(self):
        self.assertEqual(run(["z"], DOC), (0, "2.5\n", ""))
        self.assertEqual(run(["a.b[0].c"], DOC), (0, "1\n", ""))
        self.assertEqual(run([""], b"1.0"), (0, "1\n", ""))
        self.assertEqual(run([""], b"1e2"), (0, "100\n", ""))

    def test_boolean_and_null(self):
        self.assertEqual(run(["t"], DOC), (0, "true\n", ""))
        self.assertEqual(run(["n"], DOC), (0, "null\n", ""))

    def test_compact_container_keeps_order_and_utf8(self):
        self.assertEqual(
            run(["a"], DOC),
            (0, '{"b":[{"c":1},[10,20]],"s":"hi"}\n', ""),
        )
        self.assertEqual(run([""], '{"é": "ü", "b": 1}'.encode()), (0, '{"é":"ü","b":1}\n', ""))

    def test_root_selectors(self):
        self.assertEqual(run(["."], b"[1,2]"), (0, "[1,2]\n", ""))
        self.assertEqual(run([""], b"[1,2]"), (0, "[1,2]\n", ""))
        self.assertEqual(run([".[1]"], b"[1,2]"), (0, "2\n", ""))

    def test_chained_path(self):
        doc = b'{"a": {"b": [[0, 1], [2, 3]]}}'
        self.assertEqual(run(["a.b[1][0]"], doc), (0, "2\n", ""))

    def test_stdin_dash_and_file(self):
        self.assertEqual(run(["a.s", "-"], DOC), (0, "hi\n", ""))
        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
            f.write(DOC)
            name = f.name
        try:
            self.assertEqual(run(["a.s", name]), (0, "hi\n", ""))
        finally:
            os.unlink(name)

    def test_output_has_lf_only(self):
        _, out, _ = run(["a"], b'{"x": "a\\r\\nb"}')
        self.assertNotIn("\r", out)


class NotFoundTests(unittest.TestCase):
    def assertNotFound(self, path, doc):
        code, out, err = run([path], doc)
        self.assertEqual((code, out, err), (1, "", f"error: path not found: {path}\n"))

    def test_missing_key(self):
        self.assertNotFound("a.q", DOC)

    def test_index_out_of_range(self):
        self.assertNotFound("a.b[5]", DOC)

    def test_index_into_non_array(self):
        self.assertNotFound("a[0]", DOC)

    def test_key_into_non_object(self):
        self.assertNotFound("a.b.c", DOC)
        self.assertNotFound("z.q", DOC)


class ErrorTests(unittest.TestCase):
    def test_invalid_json_reports_position(self):
        code, out, err = run(["a"], b'{"a":\n  oops}')
        self.assertEqual(code, 2)
        self.assertEqual(out, "")
        self.assertTrue(err.startswith("error: invalid JSON: "), err)
        self.assertIn("line 2", err)
        self.assertEqual(err.count("\n"), 1)

    def test_nan_and_infinity_rejected(self):
        for doc in (b'{"a": NaN}', b'{"a": Infinity}', b'{"a": -Infinity}', b'{"a": 1e400}'):
            code, out, err = run(["a"], doc)
            self.assertEqual(code, 2, doc)
            self.assertIn("invalid JSON", err)
            self.assertIn("line 1 column", err)

    def test_nan_inside_string_is_fine(self):
        self.assertEqual(run(["a"], b'{"a": "NaN"}'), (0, "NaN\n", ""))

    def test_invalid_utf8(self):
        code, _, err = run([""], b'"\xff"')
        self.assertEqual(code, 2)
        self.assertIn("invalid JSON", err)

    def test_unknown_flag(self):
        self.assertEqual(run(["a", "--bogus"], DOC), (2, "", "unknown flag: --bogus\n"))

    def test_missing_path(self):
        code, out, err = run([])
        self.assertEqual((code, out), (2, ""))
        self.assertTrue(err.startswith("error: "))

    def test_bad_depth(self):
        for value in ("-1", "x", ""):
            code, _, err = run(["a", "--shape", "--depth", value], DOC)
            self.assertEqual(code, 2, value)
            self.assertTrue(err.startswith("error: "))

    def test_invalid_path_syntax(self):
        for path in ("a..b", "a.", "a[x]", "a[0", "[", "a]"):
            code, _, err = run([path], DOC)
            self.assertEqual(code, 2, path)
            self.assertIn("invalid path", err)


class HelpTests(unittest.TestCase):
    def test_help_lists_every_flag(self):
        code, out, err = run(["--help"])
        self.assertEqual((code, err), (0, ""))
        for token in ("PATH", "FILE", "--shape", "--depth", "--help"):
            self.assertIn(token, out)


class ShapeTests(unittest.TestCase):
    def shape(self, path, doc, *extra):
        code, out, err = run([path, "--shape", *extra], doc)
        self.assertEqual((code, err), (0, ""))
        return out

    def test_default_depth_two(self):
        self.assertEqual(
            self.shape("", DOC),
            "object{a: object{b: array[2], s: string}, z: number, t: boolean, n: null}\n",
        )

    def test_depth_zero(self):
        self.assertEqual(self.shape("", DOC, "--depth", "0"), "object{4 keys}\n")
        self.assertEqual(self.shape("a.b", DOC, "--depth", "0"), "array[2]\n")
        self.assertEqual(self.shape("z", DOC, "--depth", "0"), "number\n")

    def test_depth_limits_nesting(self):
        self.assertEqual(self.shape("a", DOC, "--depth", "1"), "object{b: array[2], s: string}\n")
        self.assertEqual(self.shape("a", DOC, "--depth", "2"), "object{b: array[2] of object{1 keys}, s: string}\n")

    def test_arrays_and_empties(self):
        self.assertEqual(self.shape("", b"[]"), "array[0]\n")
        self.assertEqual(self.shape("", b"[[]]", "--depth", "5"), "array[1] of array[0]\n")
        self.assertEqual(self.shape("", b"{}"), "object{}\n")
        self.assertEqual(self.shape("", b"[1,2,3]", "--depth", "1"), "array[3] of number\n")

    def test_shape_path_not_found(self):
        self.assertEqual(run(["q", "--shape"], DOC), (1, "", "error: path not found: q\n"))


if __name__ == "__main__":
    unittest.main()
