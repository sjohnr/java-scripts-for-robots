JAVAC ?= javac
SOURCES := $(wildcard src/jsonpick/*.java)
CLASSES := build/classes/jsonpick/Main.class

.PHONY: all test clean

all: $(CLASSES)

$(CLASSES): $(SOURCES)
	@mkdir -p build/classes
	$(JAVAC) --release 11 -Xlint:all -Werror -d build/classes $(SOURCES)

test: all
	bash tests/run-tests.sh

clean:
	rm -rf build
