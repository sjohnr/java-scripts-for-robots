JAVAC ?= javac
JAR ?= jar
BUILD := build

all: $(BUILD)/logsum.jar

$(BUILD)/logsum.jar: src/Logsum.java
	mkdir -p $(BUILD)/classes
	$(JAVAC) --release 17 -d $(BUILD)/classes src/Logsum.java
	$(JAR) --create --file $@ --main-class Logsum -C $(BUILD)/classes .

test: $(BUILD)/logsum.jar
	sh tests/run.sh $(BUILD)/logsum.jar

clean:
	rm -rf $(BUILD)

.PHONY: all test clean
