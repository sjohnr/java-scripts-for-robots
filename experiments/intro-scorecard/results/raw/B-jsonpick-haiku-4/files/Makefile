SOURCES := $(shell find src -name '*.java')

.PHONY: all build test clean

all: build

build: build/jsonpick.jar

build/jsonpick.jar: $(SOURCES)
	rm -rf build
	mkdir -p build/classes
	javac --release 17 -Xlint:all -d build/classes $(SOURCES)
	jar --create --file $@ --main-class jsonpick.Main -C build/classes .

test: build
	bash test/run-tests.sh

clean:
	rm -rf build
