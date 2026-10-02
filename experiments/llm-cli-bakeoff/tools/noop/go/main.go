// noop measures pure process-startup floor. No I/O, no argument-dependent
// logic beyond recognizing --help.
package main

import (
	"fmt"
	"os"
)

func main() {
	for _, arg := range os.Args[1:] {
		if arg == "--help" {
			fmt.Print("usage: noop\nprints \"ok\" and exits 0\n")
			os.Exit(0)
		}
	}
	fmt.Print("ok\n")
	os.Exit(0)
}
