# Makefile for LDAP Client

# Java compiler
JC = javac

# Compiler flags
JFLAGS = -g

# Source files
SOURCES = LDAPClient.java

# Class files
CLASSES = $(SOURCES:.java=.class)

# Default target
all: $(CLASSES)

# Compile Java source files
%.class: %.java
	$(JC) $(JFLAGS) $<

# Clean generated files
clean:
	rm -f *.class

# Run the application
run: $(CLASSES)
	java LDAPClient

# Show usage help
help:
	@echo "LDAP Client Makefile"
	@echo "-------------------"
	@echo "make        - Compile the LDAP client"
	@echo "make run    - Run the LDAP client" 
	@echo "make clean  - Remove compiled classes"
	@echo "make help   - Display this help message"

.PHONY: all clean run help