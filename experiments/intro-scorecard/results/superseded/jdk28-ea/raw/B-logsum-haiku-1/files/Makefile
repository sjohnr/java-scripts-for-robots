JAVAC ?= javac
JAR_TOOL ?= jar
SRC := $(shell find src -name '*.java')
BUILD := build

.PHONY: all jar test clean

all: jar

$(BUILD)/classes: $(SRC)
	@mkdir -p $(BUILD)/classes
	$(JAVAC) --release 17 -Xlint:all -d $(BUILD)/classes $(SRC)
	@touch $@

jar: $(BUILD)/classes
	$(JAR_TOOL) --create --file $(BUILD)/logsum.jar --main-class logsum.Main -C $(BUILD)/classes .

test: jar
	sh test/run-tests.sh

clean:
	rm -rf $(BUILD)
