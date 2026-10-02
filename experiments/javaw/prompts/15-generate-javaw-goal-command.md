Goal: I want to run any Java program I write like a native command, without typing `java`, flags, or classpaths, and without a build step in between.

I want to do it with a single command. For example:

```
$ javaw Tool.java
```

creates an executable `run-tool` in the current directory, and then:

```
$ ./run-tool arg1 arg2
```

runs Tool.java with `arg1 arg2` as its arguments. The full requirements for the tool follow.
