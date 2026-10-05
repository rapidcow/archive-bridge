IPSW ?= ipsw

A2S := NotesEditor.a2s NotesShared.a2s PencilKit.a2s

.PHONY: all clean

all: $(A2S)

%.a2s: %
	$(IPSW) macho disass $< --cache $@ > /dev/null

clean:
	rm -f $(A2S)
