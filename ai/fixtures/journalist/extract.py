#!/usr/bin/env python3
"""Reproduce single-sketch.gif from its private source object; never print contents."""

import argparse
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


SOURCE_KEY = "SHA256E-s2198593--5052a5aab8d7f68cfb4e09aeaf1fd367dedaa00d76a9317606161965d7ac3adc"
SAMPLE_SHA256 = "eb3a967e7f368fead2632706a67b173f83a1d08b858cef10b477ae8e23f42f57"
NS = {"j": "http://www.charette.com/journalist/1.0"}


def extract(source):
    """Select the 90th item of the first actual page and return its Ink member."""
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if source.stat().st_size != 2198593 or digest.hexdigest() != SOURCE_KEY.split("--")[1]:
        raise ValueError("Source does not match the recorded annex object")

    with zipfile.ZipFile(source) as archive:
        root = ET.fromstring(archive.read("journal.xml"))
        page = root.findall("./j:Pages/j:JournalPage", NS)[0]
        item = page.findall("./j:Items/*", NS)[89]
        if item.tag != "{" + NS["j"] + "}Sketch":
            raise ValueError("Selected item is not a Sketch")
        sample = archive.read(item.attrib["Ink"])

    if len(sample) != 388 or hashlib.sha256(sample).hexdigest() != SAMPLE_SHA256:
        raise ValueError("Extracted bytes do not match the recorded fixture")
    return sample


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="local source annex object or archive")
    parser.add_argument("output", type=Path, help="new output path; existing files are not overwritten")
    args = parser.parse_args()
    sample = extract(args.source)
    with args.output.open("xb") as stream:
        stream.write(sample)
    print("Extracted and verified 388 bytes.")


if __name__ == "__main__":
    main()
