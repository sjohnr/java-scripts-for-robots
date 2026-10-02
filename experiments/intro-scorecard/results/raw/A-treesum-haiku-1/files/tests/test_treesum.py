import os
import subprocess
import sys
import tempfile
import unittest

TREESUM = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "treesum")


def run(*args):
    return subprocess.run(
        [sys.executable, TREESUM, *args],
        capture_output=True,
        timeout=60,
    )


def write(root, rel, data=b""):
    path = os.path.join(root, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


class TreesumTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        write(self.root, "a.py", b"x\ny\nz\n")          # loc 3, 6 bytes
        write(self.root, "b.py", b"1\n2")               # loc 2 (no trailing newline), 3 bytes
        write(self.root, "README", b"hello\n")          # (none), loc 1, 6 bytes
        write(self.root, ".gitignore", b"x")            # (none), loc 1, 1 byte
        write(self.root, "sub/c.TXT", b"a\n\n")         # txt, loc 2, 3 bytes
        write(self.root, "sub/deeper/d.py", b"")        # py, loc 0, 0 bytes
        write(self.root, "node_modules/e.js", b"a\n")   # ignored
        write(self.root, "sub/build/f.md", b"a\n")      # ignored (nested)
        os.symlink("a.py", os.path.join(self.root, "link"))
        os.symlink("sub", os.path.join(self.root, "sublink"))

    def tearDown(self):
        self._tmp.cleanup()

    def test_default_output(self):
        r = run(self.root)
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stderr, b"")
        expected = (
            "files: 6\n"
            "dirs: 2\n"
            "by extension:\n"
            "  py  files=3 loc=5\n"
            "  (none)  files=2 loc=2\n"
            "  txt  files=1 loc=2\n"
            "largest files:\n"
            "  6 README\n"
            "  6 a.py\n"
            "  3 b.py\n"
            "  3 sub/c.TXT\n"
            "  1 .gitignore\n"
            "  0 sub/deeper/d.py\n"
        )
        self.assertEqual(r.stdout.decode("utf-8"), expected)
        self.assertNotIn(b"\r", r.stdout)

    def test_ext_limit_folds_rest_into_other_last(self):
        r = run(self.root, "--ext-limit", "1")
        self.assertEqual(r.returncode, 0)
        self.assertIn(
            b"by extension:\n  py  files=3 loc=5\n  other  files=3 loc=4\nlargest files:\n",
            r.stdout,
        )

    def test_ext_limit_zero_folds_everything(self):
        r = run(self.root, "--ext-limit=0")
        self.assertEqual(r.returncode, 0)
        self.assertIn(b"by extension:\n  other  files=6 loc=9\nlargest files:\n", r.stdout)

    def test_top_limits_largest_files(self):
        r = run(self.root, "--top", "2")
        self.assertEqual(r.returncode, 0)
        self.assertTrue(r.stdout.endswith(b"largest files:\n  6 README\n  6 a.py\n"))

    def test_top_zero_lists_none(self):
        r = run(self.root, "--top", "0")
        self.assertTrue(r.stdout.endswith(b"largest files:\n"))

    def test_extension_ties_sort_none_first_and_lowercase(self):
        root = tempfile.mkdtemp()
        try:
            write(root, "zz", b"")
            write(root, "a.a", b"")
            write(root, "x.B", b"")
            r = run(root)
            self.assertIn(
                b"by extension:\n  (none)  files=1 loc=0\n  a  files=1 loc=0\n  b  files=1 loc=0\n",
                r.stdout,
            )
        finally:
            import shutil
            shutil.rmtree(root)

    def test_empty_directory(self):
        root = tempfile.mkdtemp()
        try:
            r = run(root)
            self.assertEqual(r.returncode, 0)
            self.assertEqual(
                r.stdout,
                b"files: 0\ndirs: 0\nby extension:\nlargest files:\n",
            )
        finally:
            import shutil
            shutil.rmtree(root)

    def test_default_dir_is_current_directory(self):
        r = subprocess.run(
            [sys.executable, TREESUM],
            cwd=self.root,
            capture_output=True,
            timeout=60,
        )
        self.assertEqual(r.returncode, 0)
        self.assertTrue(r.stdout.startswith(b"files: 6\ndirs: 2\n"))
        self.assertIn(b"  6 README\n", r.stdout)

    def test_missing_dir_is_usage_error(self):
        missing = os.path.join(self.root, "nope")
        r = run(missing)
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")
        self.assertEqual(r.stderr, f"error: cannot read {missing}\n".encode())

    def test_file_as_dir_is_usage_error(self):
        r = run(os.path.join(self.root, "a.py"))
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")

    def test_unknown_flag(self):
        r = run("--bogus", self.root)
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")
        self.assertEqual(r.stderr, b"unknown flag: --bogus\n")

    def test_bad_numeric_values(self):
        for args in (["--top", "abc"], ["--top", "-1"], ["--ext-limit", ""]):
            r = run(*args, self.root)
            self.assertEqual(r.returncode, 2, args)
            self.assertEqual(r.stdout, b"", args)

    def test_flag_missing_value(self):
        r = run(self.root, "--top")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")

    def test_extra_positional_is_usage_error(self):
        r = run(self.root, self.root)
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")

    def test_help_lists_every_flag_and_ignore_entry(self):
        r = run("--help")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stderr, b"")
        for token in (b"DIR", b"--top", b"--ext-limit", b"--help", b"node_modules", b".vscode"):
            self.assertIn(token, r.stdout)


if __name__ == "__main__":
    unittest.main()
