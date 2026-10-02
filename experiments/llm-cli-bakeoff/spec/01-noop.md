# noop

Purpose: measure pure process-startup floor. Does no I/O, no argument-dependent logic.

## Usage

```
noop
```

Any arguments are ignored (still exit 0) EXCEPT `--help`, which prints usage and exits 0.

## Behavior

- stdout: exactly `ok\n`
- stderr: empty
- exit code: 0

## `--help`

stdout (exact, byte-for-byte across languages since there is nothing language-specific to say):
```
usage: noop
prints "ok" and exits 0
```
exit code: 0
