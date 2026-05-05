# Makefile pour le binaire `claudine` (PAPPL printer-application).
# Construit dans le stage `build` du Dockerfile, puis copié dans le stage final.

CC      ?= gcc
PKG_CONFIG ?= pkg-config

# pkg-config tire les bonnes flags pour libpappl (qui dépend de cups, avahi).
CFLAGS  ?= -O2 -Wall -Wextra
CFLAGS  += $(shell $(PKG_CONFIG) --cflags pappl)
LDFLAGS += $(shell $(PKG_CONFIG) --libs pappl) -lpthread

claudine: claudine.c
	$(CC) $(CFLAGS) -o $@ claudine.c $(LDFLAGS)

clean:
	rm -f claudine *.o

.PHONY: clean
