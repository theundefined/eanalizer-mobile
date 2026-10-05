#!/usr/bin/env python3
"""Upload a signed .aab to a Google Play track (used by .github/workflows/build.yml).

Non-interactive and idempotent: re-running for a versionCode that is already
uploaded and assigned to the track is a no-op, so a failed or repeated CI job
can simply be re-run.

Credentials come from Application Default Credentials. In CI these are
short-lived Workload Identity Federation credentials set up by
google-github-actions/auth (no long-lived service account key is stored).

Usage: publish_to_play.py --aab app.aab --version-code 64 [--track internal]
                          [--release-notes-file notes.txt]
"""
import argparse
import sys

import google.auth
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
# Play Console limit for "What's new" text per language.
MAX_NOTES_LEN = 500


def read_notes(path):
    if not path:
        return None
    with open(path, encoding="utf-8") as f:
        text = f.read().strip()
    if len(text) > MAX_NOTES_LEN:
        text = text[: MAX_NOTES_LEN - 1].rstrip() + "…"
    return text or None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aab", required=True, help="Path to the signed .aab")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--package", default="com.theundefined.eanalizer")
    parser.add_argument("--track", default="internal", choices=["internal", "alpha", "beta"])
    parser.add_argument("--release-notes-file", default=None)
    args = parser.parse_args()

    notes = read_notes(args.release_notes_file)
    creds, _ = google.auth.default(scopes=SCOPES)
    service = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
    edits = service.edits()

    edit_id = edits.insert(body={}, packageName=args.package).execute()["id"]
    print(f"Opened edit {edit_id} for {args.package}")

    try:
        existing = edits.bundles().list(editId=edit_id, packageName=args.package).execute()
        uploaded_codes = {b["versionCode"] for b in existing.get("bundles", [])}

        if args.version_code in uploaded_codes:
            print(f"versionCode {args.version_code} already uploaded, skipping upload")
        else:
            # .aab has no registered mimetype; Google's samples use octet-stream.
            media = MediaFileUpload(args.aab, mimetype="application/octet-stream", resumable=True)
            upload = (
                edits.bundles()
                .upload(editId=edit_id, packageName=args.package, media_body=media)
                .execute()
            )
            if upload["versionCode"] != args.version_code:
                raise RuntimeError(
                    f"Uploaded bundle has versionCode {upload['versionCode']}, "
                    f"expected {args.version_code}"
                )
            print(f"Uploaded bundle, versionCode={args.version_code}")

        track = edits.tracks().get(
            editId=edit_id, track=args.track, packageName=args.package
        ).execute()
        for rel in track.get("releases", []):
            if str(args.version_code) in rel.get("versionCodes", []) and rel.get("status") == "completed":
                print(f"versionCode {args.version_code} is already live on '{args.track}', nothing to do")
                edits.delete(editId=edit_id, packageName=args.package).execute()
                return

        release = {"versionCodes": [str(args.version_code)], "status": "completed"}
        if notes:
            # Notes must use a language that exists in the store listing; the
            # default listing language always does.
            language = edits.details().get(
                editId=edit_id, packageName=args.package
            ).execute()["defaultLanguage"]
            release["releaseNotes"] = [{"language": language, "text": notes}]

        edits.tracks().update(
            editId=edit_id,
            track=args.track,
            packageName=args.package,
            body={"releases": [release]},
        ).execute()
        edits.commit(editId=edit_id, packageName=args.package).execute()
        print(f"Committed: versionCode {args.version_code} is live on '{args.track}'")
    except (HttpError, RuntimeError) as e:
        print(f"Publishing failed, discarding edit {edit_id}: {e}", file=sys.stderr)
        try:
            edits.delete(editId=edit_id, packageName=args.package).execute()
        except HttpError:
            pass
        sys.exit(1)


if __name__ == "__main__":
    main()
