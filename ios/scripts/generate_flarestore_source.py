#!/usr/bin/env python3
"""Create an AltStore/FlareStore source using the actual compiled iOS Info.plist.

Only publish assets placed in the distribution directory, never source code or
Google OAuth tokens. This script uses standard-library Python.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import plistlib
from pathlib import Path
from urllib.parse import quote


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plist", type=Path, required=True)
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--notes", default="Actualización de CREA Reader para iOS.")
    arguments = parser.parse_args()

    with arguments.plist.open("rb") as handle:
        info = plistlib.load(handle)

    bundle_id = info.get("CFBundleIdentifier")
    version = str(info.get("CFBundleShortVersionString", ""))
    build = str(info.get("CFBundleVersion", ""))
    if bundle_id != "org.crea.reader.ios" or not version or not build:
        raise ValueError("App id/version/build do not match the CREA Reader iOS package")
    if arguments.ipa.stat().st_size < 100_000:
        raise ValueError("Packaged IPA is missing or unexpectedly small")

    base = arguments.base_url.rstrip("/") + "/"
    ipa_filename = arguments.ipa.name
    date = dt.datetime.now(dt.timezone.utc).date().isoformat()
    size = arguments.ipa.stat().st_size
    icon = base + "icon.png"
    download = base + quote(ipa_filename)
    source = {
        "name": "CREA Reader",
        "identifier": "org.crea.reader.source",
        "sourceURL": base + "source.json",
        "subtitle": "Biblioteca y lector educativo de CREA",
        "description": "Versiones oficiales de CREA Reader iOS para instalar y actualizar mediante FlareStore.",
        "website": "https://creanicaragua.org",
        "iconURL": icon,
        "tintColor": "#2679E8",
        "apps": [
            {
                "name": "CREA Reader",
                "bundleIdentifier": bundle_id,
                "developerName": "CREA Nicaragua",
                "subtitle": "Lectura EPUB y PDF, Google Drive y Comunidad",
                "localizedDescription": (
                    "Lector educativo de CREA para iPhone y iPad. Biblioteca EPUB/PDF, "
                    "catálogo Google Drive, lectura en línea y progreso de lectura."
                ),
                "iconURL": icon,
                "tintColor": "#2679E8",
                "category": "books",
                "beta": True,
                # Legacy source fields for older sideloading clients:
                "version": version,
                "versionDate": date,
                "size": size,
                "downloadURL": download,
                # New format: the latest compatible version appears first.
                "versions": [
                    {
                        "version": version,
                        "buildVersion": build,
                        "date": date,
                        "localizedDescription": arguments.notes,
                        "downloadURL": download,
                        "size": size,
                        "minOSVersion": "17.0",
                    }
                ],
            }
        ],
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"FlareStore: {bundle_id} v{version} ({build}), {size} bytes")
    print(f"Source: {base}source.json")
    print(f"IPA: {download}")


if __name__ == "__main__":
    main()
