// Command javaw generates a POSIX sh launcher script for a Java program.
package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

const usage = `Usage: javaw [options] INPUT...

Generate a launcher script (run-<name>) for each Java source file or jar.

Options:
  --jar              inputs are jars: launch with java -jar
  --classpath PATH   add -cp PATH to the launch command (recorded as-is)
  --java-home DIR    use DIR/bin/java instead of java from the PATH
  --jvm-opts "OPTS"  JVM options placed before the program
  --min-java N       fail with a clear error if Java is older than N
  -o, --output NAME  output file, or directory (ends in / or exists)
  --force            overwrite existing files
  -h, --help         show this help
`

type options struct {
	jar, force, help                              bool
	classpath, javaHome, jvmOpts, minJava, output string
	inputs                                        []string
}

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	o, err := parse(args)
	if err != nil {
		fmt.Fprintf(stderr, "javaw: %v\nTry 'javaw --help'.\n", err)
		return 2
	}
	if o.help {
		fmt.Fprint(stdout, usage)
		return 0
	}
	header := "javaw"
	for _, a := range args {
		header += " " + shQuote(a)
	}
	for _, in := range o.inputs {
		out, err := outputPath(o, in)
		if err == nil {
			err = write(out, wrapper(o, in, header), o.force)
		}
		if err != nil {
			fmt.Fprintf(stderr, "javaw: %v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "wrote %s\n", out)
	}
	return 0
}

func parse(args []string) (*options, error) {
	o := &options{}
	for i := 0; i < len(args); i++ {
		a := args[i]
		if strings.ContainsAny(a, "\n\r") {
			return nil, fmt.Errorf("arguments may not contain newlines: %q", a)
		}
		if a == "--" {
			o.inputs = append(o.inputs, args[i+1:]...)
			break
		}
		if !strings.HasPrefix(a, "-") || a == "-" {
			o.inputs = append(o.inputs, a)
			continue
		}
		name, val, hasVal := strings.Cut(a, "=")
		flags := map[string]*bool{"--jar": &o.jar, "--force": &o.force, "-h": &o.help, "--help": &o.help}
		values := map[string]*string{"-o": &o.output, "--output": &o.output, "--classpath": &o.classpath,
			"--java-home": &o.javaHome, "--jvm-opts": &o.jvmOpts, "--min-java": &o.minJava}
		if f, ok := flags[name]; ok && !hasVal {
			*f = true
		} else if ok {
			return nil, fmt.Errorf("option %s does not take a value", name)
		} else if v, ok := values[name]; ok {
			if !hasVal {
				if i++; i >= len(args) {
					return nil, fmt.Errorf("option %s requires a value", name)
				}
				val = args[i]
			}
			*v = val
		} else {
			return nil, fmt.Errorf("unknown option: %s", a)
		}
	}
	if o.help {
		return o, nil
	}
	if n, err := strconv.Atoi(o.minJava); o.minJava != "" && (err != nil || n < 1) {
		return nil, fmt.Errorf("--min-java must be a positive integer, got %q", o.minJava)
	}
	if len(o.inputs) == 0 {
		return nil, errors.New("missing input file (a .java source file, or a jar with --jar)")
	}
	if len(o.inputs) > 1 && o.output != "" && !isDir(o.output) {
		return nil, fmt.Errorf("with several inputs, -o must be a directory: %s", o.output)
	}
	return o, nil
}

func isDir(p string) bool {
	st, err := os.Stat(p)
	return strings.HasSuffix(p, "/") || (err == nil && st.IsDir())
}

func outputPath(o *options, in string) (string, error) {
	base := filepath.Base(in)
	name := kebab(strings.TrimSuffix(base, filepath.Ext(base)))
	if name == "" {
		return "", fmt.Errorf("cannot derive a name from %q; use -o", in)
	}
	name = "run-" + name
	switch {
	case o.output == "":
		return name, nil
	case isDir(o.output):
		return filepath.Join(o.output, name), nil
	}
	return o.output, nil
}

// kebab converts FooBar, HTTPServer, Example1_Assert to foo-bar, http-server, example1-assert.
func kebab(s string) string {
	r := []rune(s)
	var b strings.Builder
	sep := false
	for i, c := range r {
		if strings.ContainsRune("_. -", c) {
			sep = true
			continue
		}
		if i > 0 && unicode.IsUpper(c) {
			p := r[i-1]
			nextLower := i+1 < len(r) && unicode.IsLower(r[i+1])
			sep = sep || unicode.IsLower(p) || unicode.IsDigit(p) || (unicode.IsUpper(p) && nextLower)
		}
		if sep && b.Len() > 0 {
			b.WriteByte('-')
		}
		sep = false
		b.WriteRune(unicode.ToLower(c))
	}
	return b.String()
}

var safe = regexp.MustCompile(`^[A-Za-z0-9_@%+=:,./-]+$`)

// shQuote returns s quoted for POSIX sh, unchanged when no quoting is needed.
func shQuote(s string) string {
	if safe.MatchString(s) {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

// resolve sets $dir to the directory of the wrapper's real file.
const resolve = `self=$0
while [ -L "$self" ]; do
  link=$(readlink -- "$self")
  case $link in
    /*) self=$link ;;
    *) self=$(dirname -- "$self")/$link ;;
  esac
done
dir=$(CDPATH= cd -P -- "$(dirname -- "$self")" && pwd -P) || exit 1
`

func wrapper(o *options, in, header string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "#!/bin/sh\n# Generated by: %s\n%s", header, resolve)
	java := "java"
	if o.javaHome != "" {
		java = shQuote(o.javaHome) + "/bin/java"
	}
	if o.minJava != "" {
		fmt.Fprintf(&b, `v=$(%s -version 2>&1 | sed -n 's/^[A-Za-z]* version "\([^"]*\)".*/\1/p' | head -n 1)
case $v in 1.*) n=${v#1.} ;; *) n=$v ;; esac
n=${n%%%%[!0-9]*}
if [ -z "$n" ] || [ "$n" -lt %s ]; then
  printf '%%s\n' "${0##*/}: requires Java %s or newer (found: ${v:-none})" >&2
  exit 1
fi
`, java, o.minJava, o.minJava)
	}
	cmd := "exec " + java
	if o.jvmOpts != "" {
		cmd += " " + o.jvmOpts // verbatim, so the shell splits the options
	}
	if o.classpath != "" {
		cmd += " -cp " + shQuote(o.classpath)
	}
	if o.jar {
		cmd += " -jar"
	}
	prog := filepath.Base(in)
	if safe.MatchString(prog) {
		prog = `"$dir/` + prog + `"`
	} else {
		prog = `"$dir"/` + shQuote(prog)
	}
	fmt.Fprintf(&b, "%s %s \"$@\"\n", cmd, prog)
	return b.String()
}

func write(path, content string, force bool) error {
	flag := os.O_WRONLY | os.O_CREATE | os.O_EXCL
	if force {
		flag = os.O_WRONLY | os.O_CREATE | os.O_TRUNC
	}
	f, err := os.OpenFile(path, flag, 0o755)
	if errors.Is(err, os.ErrExist) {
		return fmt.Errorf("%s already exists (use --force to overwrite)", path)
	} else if err != nil {
		return err
	}
	if _, err = f.WriteString(content); err == nil {
		err = f.Chmod(0o755)
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}
