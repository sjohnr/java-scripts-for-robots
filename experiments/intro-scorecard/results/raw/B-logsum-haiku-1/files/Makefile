JAR := build/logsum.jar
SOURCES := $(wildcard src/logsum/*.java)

.PHONY: all test clean

all: $(JAR)

$(JAR): $(SOURCES)
	rm -rf build/classes
	mkdir -p build/classes
	javac --release 17 -Xlint:all -d build/classes $(SOURCES)
	jar --create --file $@ --main-class logsum.Main -C build/classes .

test: $(JAR)
	sh tests/run.sh $(JAR)

clean:
	rm -rf build
