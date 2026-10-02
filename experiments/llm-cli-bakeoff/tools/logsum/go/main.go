// logsum prints a streaming summary of a log file (or stdin).
package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"regexp"
	"sort"
	"strings"
)

const helpText = `usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]

FILE omitted or "-" means read stdin.

flags:
  --since TIMESTAMP   only consider lines with TIMESTAMP >= this value
  --level LEVEL       only consider lines at exactly this level
  --top N             number of top error groups to show (default 5)
`

var levels = []string{"DEBUG", "INFO", "WARN", "ERROR"}

var (
	uuidRe  = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)
	digitRe = regexp.MustCompile(`[0-9]+`)
)

// hexBlobRe matches runs of 6+ hex digits containing at least one a-f/A-F
// letter, word-boundary delimited, per spec: \b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\b
var hexBlobRe = regexp.MustCompile(`\b[0-9a-fA-F]{6,}\b`)

func isValidTimestamp(s string) bool {
	// YYYY-MM-DDTHH:MM:SS, fixed-width, minimal validation of digits/format
	if len(s) != 19 {
		return false
	}
	if s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':' {
		return false
	}
	for i, c := range s {
		switch i {
		case 4, 7, 10, 13, 16:
			continue
		default:
			if c < '0' || c > '9' {
				return false
			}
		}
	}
	return true
}

func main() {
	args := os.Args[1:]
	for _, a := range args {
		if a == "--help" {
			fmt.Print(helpText)
			os.Exit(0)
		}
	}

	var file string
	hasFile := false
	var since, level string
	hasSince, hasLevel := false, false
	top := 5

	i := 0
	for i < len(args) {
		a := args[i]
		switch {
		case a == "--since":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --since requires a value")
				os.Exit(2)
			}
			since = args[i+1]
			hasSince = true
			i += 2
		case a == "--level":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --level requires a value")
				os.Exit(2)
			}
			level = args[i+1]
			hasLevel = true
			i += 2
		case a == "--top":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --top requires a value")
				os.Exit(2)
			}
			n := 0
			_, err := fmt.Sscanf(args[i+1], "%d", &n)
			if err != nil {
				fmt.Fprintf(os.Stderr, "error: invalid value for --top: %s\n", args[i+1])
				os.Exit(2)
			}
			top = n
			i += 2
		case strings.HasPrefix(a, "--"):
			fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
			os.Exit(2)
		default:
			if hasFile {
				fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
				os.Exit(2)
			}
			file = a
			hasFile = true
			i++
		}
	}

	if hasSince && !isValidTimestamp(since) {
		fmt.Fprintf(os.Stderr, "error: invalid value for --since: %s\n", since)
		os.Exit(2)
	}
	if hasLevel {
		valid := false
		for _, l := range levels {
			if l == level {
				valid = true
				break
			}
		}
		if !valid {
			fmt.Fprintf(os.Stderr, "error: invalid value for --level: %s\n", level)
			os.Exit(2)
		}
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

	total := 0
	byLevel := map[string]int{"DEBUG": 0, "INFO": 0, "WARN": 0, "ERROR": 0}
	var first, last string
	haveFirst := false

	type errGroup struct {
		count   int
		display string
	}
	errGroups := map[string]*errGroup{}
	var errOrder []string // normalized keys in first-seen order (not required for output but harmless)

	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 64*1024*1024)
	for sc.Scan() {
		line := sc.Text()
		ts, lv, msg, ok := parseLine(line)
		if !ok {
			continue
		}
		if hasSince && ts < since {
			continue
		}
		if hasLevel && lv != level {
			continue
		}

		total++
		byLevel[lv]++
		if !haveFirst {
			first = ts
			haveFirst = true
		}
		last = ts

		if lv == "ERROR" {
			norm := normalizeMessage(msg)
			g, exists := errGroups[norm]
			if !exists {
				g = &errGroup{display: msg}
				errGroups[norm] = g
				errOrder = append(errOrder, norm)
			}
			g.count++
		}
	}

	var out strings.Builder
	fmt.Fprintf(&out, "total: %d\n", total)
	fmt.Fprintf(&out, "by level: DEBUG=%d INFO=%d WARN=%d ERROR=%d\n",
		byLevel["DEBUG"], byLevel["INFO"], byLevel["WARN"], byLevel["ERROR"])
	if total == 0 {
		fmt.Fprintf(&out, "first: n/a\n")
		fmt.Fprintf(&out, "last: n/a\n")
	} else {
		fmt.Fprintf(&out, "first: %s\n", first)
		fmt.Fprintf(&out, "last: %s\n", last)
	}
	fmt.Fprintf(&out, "top errors:\n")

	type sortableGroup struct {
		count   int
		display string
	}
	var sg []sortableGroup
	for _, norm := range errOrder {
		g := errGroups[norm]
		sg = append(sg, sortableGroup{g.count, g.display})
	}
	sort.Slice(sg, func(i, j int) bool {
		if sg[i].count != sg[j].count {
			return sg[i].count > sg[j].count
		}
		return sg[i].display < sg[j].display
	})
	n := top
	if n > len(sg) {
		n = len(sg)
	}
	if n < 0 {
		n = 0
	}
	for _, g := range sg[:n] {
		fmt.Fprintf(&out, "  %d %s\n", g.count, g.display)
	}

	os.Stdout.WriteString(out.String())
}

// parseLine parses a log line into (timestamp, level, message, ok).
func parseLine(line string) (string, string, string, bool) {
	// TIMESTAMP LEVEL MESSAGE, split on first two spaces only
	i1 := strings.IndexByte(line, ' ')
	if i1 < 0 {
		return "", "", "", false
	}
	ts := line[:i1]
	rest := line[i1+1:]
	i2 := strings.IndexByte(rest, ' ')
	if i2 < 0 {
		return "", "", "", false
	}
	lv := rest[:i2]
	msg := rest[i2+1:]

	if !isValidTimestamp(ts) {
		return "", "", "", false
	}
	validLevel := false
	for _, l := range levels {
		if l == lv {
			validLevel = true
			break
		}
	}
	if !validLevel {
		return "", "", "", false
	}
	return ts, lv, msg, true
}

func normalizeMessage(msg string) string {
	msg = uuidRe.ReplaceAllString(msg, "<uuid>")
	msg = hexBlobRe.ReplaceAllStringFunc(msg, func(s string) string {
		hasLetter := false
		for _, c := range s {
			if (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') {
				hasLetter = true
				break
			}
		}
		if hasLetter {
			return "<hex>"
		}
		return s
	})
	msg = digitRe.ReplaceAllString(msg, "<num>")
	return msg
}
