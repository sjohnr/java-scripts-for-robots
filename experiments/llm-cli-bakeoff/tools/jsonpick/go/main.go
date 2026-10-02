// jsonpick extracts a value by path from a JSON document without a jq
// dependency, using a hand-rolled order-preserving JSON parser.
package main

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

const helpText = `usage: jsonpick PATH [FILE] [--shape] [--depth N]

PATH is required (dot-separated keys, [N] for array index, e.g. a.b[0].c).
PATH of "." or "" selects the whole document.
FILE omitted or "-" means read stdin.

flags:
  --shape     print a depth-limited type/length summary instead of the value
  --depth N   depth for --shape (default 2)
`

func main() {
	args := os.Args[1:]
	for _, a := range args {
		if a == "--help" {
			fmt.Print(helpText)
			os.Exit(0)
		}
	}

	var pathArg string
	hasPath := false
	var file string
	hasFile := false
	shape := false
	depth := 2

	i := 0
	for i < len(args) {
		a := args[i]
		switch {
		case a == "--shape":
			shape = true
			i++
		case a == "--depth":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --depth requires a value")
				os.Exit(2)
			}
			n, err := strconv.Atoi(args[i+1])
			if err != nil {
				fmt.Fprintf(os.Stderr, "error: invalid value for --depth: %s\n", args[i+1])
				os.Exit(2)
			}
			depth = n
			i += 2
		case strings.HasPrefix(a, "--"):
			fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
			os.Exit(2)
		default:
			if !hasPath {
				pathArg = a
				hasPath = true
			} else if !hasFile {
				file = a
				hasFile = true
			} else {
				fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
				os.Exit(2)
			}
			i++
		}
	}

	if !hasPath {
		fmt.Fprintln(os.Stderr, "error: missing argument: PATH")
		os.Exit(2)
	}

	var r io.Reader
	if !hasFile || file == "-" {
		r = os.Stdin
	} else {
		f, err := os.Open(file)
		if err != nil {
			fmt.Fprintf(os.Stderr, "error: cannot read %s\n", file)
			os.Exit(2)
		}
		defer f.Close()
		r = f
	}

	data, err := io.ReadAll(r)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: cannot read input: %v\n", err)
		os.Exit(2)
	}

	root, perr := parseJSON(data)
	if perr != nil {
		fmt.Fprintf(os.Stderr, "error: invalid JSON: %s\n", perr.Error())
		os.Exit(2)
	}

	segs := parsePath(pathArg)
	target, ok := navigate(root, segs)
	if !ok {
		fmt.Fprintf(os.Stderr, "error: path not found: %s\n", pathArg)
		os.Exit(1)
	}

	if shape {
		fmt.Println(shapeOf(target, depth))
		return
	}

	switch target.Kind {
	case KString:
		fmt.Println(target.Str)
	case KNumber:
		fmt.Println(formatNumber(target.Num))
	case KBool:
		if target.Bool {
			fmt.Println("true")
		} else {
			fmt.Println("false")
		}
	case KNull:
		fmt.Println("null")
	case KArray, KObject:
		var sb strings.Builder
		serializeCompact(target, &sb)
		fmt.Println(sb.String())
	}
}
