JAVAC ?= javac
JAR ?= jar
BUILD := build

all: $(BUILD)/treesum.jar

$(BUILD)/treesum.jar: src/Treesum.java
	mkdir -p $(BUILD)/classes
	$(JAVAC) --release 17 -d $(BUILD)/classes src/Treesum.java
	$(JAR) --create --file $@ --main-class Treesum -C $(BUILD)/classes .

clean:
	rm -rf $(BUILD)

.PHONY: all clean
