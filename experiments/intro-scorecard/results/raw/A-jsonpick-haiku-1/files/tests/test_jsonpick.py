import os
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(ROOT, "jsonpick")


def run(args, stdin=b""):
    proc = subprocess.run([sys.executable, TOOL] + args, input=stdin,
                          capture_output=True)
    return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")


class JsonpickTests(unittest.TestCase):
    def check(self, args, stdin, code, out, err=""):
        self.assertEqual(run(args, stdin.encode("utf-8")), (code, out, err))

    def test_string_printed_raw(self):
        self.check(["a.b"], '{"a":{"b":"hi there"}}', 0, "hi there\n")

    def test_number_boolean_null(self):
        self.check(["n"], '{"n":42}', 0, "42\n")
        self.check(["n"], '{"n":1.50}', 0, "1.5\n")
        self.check(["n"], '{"n":1.0}', 0, "1\n")
        self.check(["n"], '{"n":1e3}', 0, "1000\n")
        self.check(["n"], '{"n":-0.25}', 0, "-0.25\n")
        self.check(["n"], '{"n":0.1234567890123456789}', 0, "0.1234567890123456789\n")
        self.check(["b"], '{"b":true}', 0, "true\n")
        self.check(["z"], '{"z":null}', 0, "null\n")

    def test_container_compact_and_ordered(self):
        self.check(["."], '{"z": 1, "a": [1, "x y", {"k": null}]}', 0,
                   '{"z":1,"a":[1,"x y",{"k":null}]}\n')
        self.check([""], '[ 1 , 2 ]', 0, "[1,2]\n")

    def test_index_and_root(self):
        self.check(["a[0][1].c"], '{"a":[[0,{"c":7}]]}', 0, "7\n")
        self.check(["[1]"], '[5,6]', 0, "6\n")
        self.check([".a"], '{"a":3}', 0, "3\n")

    def test_not_found(self):
        doc = '{"a":[1],"o":{}}'
        for path in ["x", "a[1]", "a.b", "a[0].b", "o.k"]:
            self.check([path], doc, 1, "", f"error: path not found: {path}\n")

    def test_invalid_json(self):
        code, out, err = run(["a"], b'{"a": }')
        self.assertEqual((code, out), (2, ""))
        self.assertTrue(err.startswith("error: invalid JSON: "))
        self.assertIn("line 1 column", err)
        self.assertEqual(err.count("\n"), 1)

    def test_nan_rejected(self):
        code, out, err = run(["a"], b'{"a": NaN}')
        self.assertEqual((code, out), (2, ""))
        self.assertIn("invalid JSON", err)

    def test_unknown_flag(self):
        self.check(["--nope", "a"], "{}", 2, "", "unknown flag: --nope\n")

    def test_usage_errors(self):
        self.assertEqual(run([], b"")[0], 2)
        self.assertEqual(run(["a", "--depth", "x"], b"{}")[0], 2)
        self.assertEqual(run(["a..b"], b"{}")[0], 2)
        self.assertEqual(run(["a[x]"], b"{}")[0], 2)

    def test_help(self):
        code, out, err = run(["--help"])
        self.assertEqual((code, err), (0, ""))
        for flag in ["PATH", "FILE", "--shape", "--depth", "--help"]:
            self.assertIn(flag, out)

    def test_file_argument(self):
        path = os.path.join(ROOT, "tests", "_tmp_doc.json")
        with open(path, "w", encoding="utf-8") as f:
            f.write('{"k": "v"}')
        try:
            self.check(["k", path], "", 0, "v\n")
            self.check(["k", "-"], '{"k": "w"}', 0, "w\n")
        finally:
            os.remove(path)

    def test_shape_default_depth(self):
        doc = '{"a":{"b":[{"c":1}]},"s":"x","e":[],"o":{}}'
        self.check(["--shape", "."], doc, 0,
                   "object{a: object{b: array[1]}, s: string, e: array[0], o: object{}}\n")

    def test_shape_depths(self):
        doc = '{"a":{"b":[{"c":1}]}}'
        self.check(["--shape", "--depth", "0", "."], doc, 0, "object{1 keys}\n")
        self.check(["--shape", "--depth=1", "."], doc, 0, "object{a: object{1 keys}}\n")
        self.check(["--shape", "--depth", "3", "."], doc, 0,
                   "object{a: object{b: array[1] of object{1 keys}}}\n")
        self.check(["--shape", "--depth", "4", "."], doc, 0,
                   "object{a: object{b: array[1] of object{c: number}}}\n")
        self.check(["--shape", "--depth", "0", "a.b"], doc, 0, "array[1]\n")

    def test_shape_scalars_and_path(self):
        self.check(["--shape", "x"], '{"x":[true,null]}', 0,
                   "array[2] of boolean\n")
        self.check(["--shape", "x[0]"], '{"x":[false]}', 0, "boolean\n")
        self.check(["--shape", "x"], '{"x":[]}', 0, "array[0]\n")
        self.check(["--shape", "x"], '{"x":"s"}', 0, "string\n")

    def test_shape_not_found(self):
        self.check(["--shape", "q"], '{}', 1, "", "error: path not found: q\n")


if __name__ == "__main__":
    unittest.main()
