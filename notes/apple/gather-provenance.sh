#!/bin/sh
# Gather macOS / Notes.app / toolchain version info worth citing in a
# "Provenance" section when writing up reverse-engineering notes under
# notes/apple/. Run this ON the Mac being examined - it only reads local
# system info, nothing destructive, no network access.
#
# Usage: ./gather-provenance.sh [bundle-id-or-app-path ...]
#   With no arguments, reports on /System/Applications/Notes.app and the
#   handful of frameworks it links against that are relevant to the
#   com.apple.drawing.2 notes (PencilKit, PaperKit, NotesEditor,
#   NotesShared, NotesSupport, NotesHTML). Pass one or more .app or
#   .framework paths to report on those instead/as well.

set -eu

NOTES_APP="/System/Applications/Notes.app"
DEFAULT_FRAMEWORKS="
/System/Library/Frameworks/PencilKit.framework
/System/Library/Frameworks/PaperKit.framework
/System/Library/PrivateFrameworks/NotesEditor.framework
/System/Library/PrivateFrameworks/NotesShared.framework
/System/Library/PrivateFrameworks/NotesSupport.framework
/System/Library/PrivateFrameworks/NotesHTML.framework
"

hr() { printf '\n## %s\n\n' "$1"; }

bundle_version() {
	# $1 = path to a .app or .framework bundle
	plist="$1/Contents/Info.plist"
	[ -f "$plist" ] || plist="$1/Resources/Info.plist"
	if [ ! -f "$plist" ]; then
		printf '%s\tNO Info.plist FOUND\n' "$1"
		return
	fi
	short=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist" 2>/dev/null || echo '?')
	build=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$plist" 2>/dev/null || echo '?')
	minos=$(/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' "$plist" 2>/dev/null || echo '')
	printf '%s\tversion %s, build %s%s\n' "$1" "$short" "$build" "${minos:+ (MinimumOSVersion $minos)}"
}

echo "# Provenance report"
echo
echo "Generated $(date -u +'%Y-%m-%dT%H:%M:%SZ') on $(hostname)."

hr "macOS"
sw_vers

hr "Kernel / hardware"
uname -a
echo
sysctl -n hw.model 2>/dev/null | sed 's/^/hw.model: /'
sysctl -n machdep.cpu.brand_string 2>/dev/null | sed 's/^/cpu.brand_string: /' || true

hr "System Integrity Protection"
csrutil status 2>&1 || true

hr "App / framework bundle versions"
bundle_version "$NOTES_APP"
for fw in $DEFAULT_FRAMEWORKS; do
	bundle_version "$fw"
done
for extra in "$@"; do
	bundle_version "$extra"
done

hr "Notes.app linked frameworks (otool -L)"
notes_bin="$NOTES_APP/Contents/MacOS/Notes"
if [ -x "$notes_bin" ]; then
	otool -L "$notes_bin" 2>&1
else
	echo "Notes binary not found/executable at $notes_bin"
fi

hr "dyld shared cache"
for cache in \
	/System/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e \
	/System/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_x86_64 \
	/System/Library/dyld/dyld_shared_cache_arm64e \
	/System/Library/dyld/dyld_shared_cache_x86_64
do
	if [ -f "$cache" ]; then
		printf '%s\t%s bytes\n' "$cache" "$(stat -f%z "$cache" 2>/dev/null || echo '?')"
		if command -v ipsw >/dev/null 2>&1; then
			ipsw dyld info "$cache" 2>&1 | grep -iE 'uuid|platform|version' || true
		fi
	fi
done

hr "Reverse-engineering toolchain"
command -v otool >/dev/null 2>&1 && { printf 'otool: %s\n' "$(command -v otool)"; }
command -v nm    >/dev/null 2>&1 && { printf 'nm:    %s\n' "$(command -v nm)"; }
command -v ipsw  >/dev/null 2>&1 && { printf 'ipsw:  '; ipsw version 2>&1 | tr '\n' ' '; echo; }
pkgutil --pkg-info=com.apple.pkg.CLTools_Executables 2>/dev/null || echo "Xcode Command Line Tools package info not found"

echo
echo "# End of report"
