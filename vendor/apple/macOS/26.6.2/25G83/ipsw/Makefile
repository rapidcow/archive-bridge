IPSW ?= ipsw
DSC  ?= /System/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e

image_NotesEditor  := /System/Library/PrivateFrameworks/NotesEditor.framework/Versions/A/NotesEditor
image_NotesShared  := /System/Library/PrivateFrameworks/NotesShared.framework/Versions/A/NotesShared
image_PencilKit    := /System/Library/Frameworks/PencilKit.framework/Versions/A/PencilKit

A2S := NotesEditor.a2s NotesShared.a2s PencilKit.a2s

.PHONY: all clean

all: $(A2S)

%.a2s: %
	$(IPSW) dyld disass $(DSC) --image $(image_$*) --cache $@ --quiet > /dev/null

clean:
	rm -f $(A2S)
