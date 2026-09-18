#!/usr/bin/env python3
"""Parsing and JSON work for tools/release.sh.

release.sh orchestrates; this does the two jobs a shell script does badly:
reading aapt2's indented xmltree dump, and emitting/inspecting JSON. jq is not
installed on the build machine and Python already is (tools/gen_font57.py,
tools/make_carbon.py), so this is the dependency that was already paid for.

Every subcommand exits non-zero on failure and prints one line saying what it
checked. Nothing here is allowed to "pass with a warning": release.sh treats
every exit code as a gate.
"""

import argparse
import hashlib
import json
import re
import sys

ANDROID_NS = "http://schemas.android.com/apk/res/android"


# -- aapt2 xmltree -----------------------------------------------------------

class Node:
    __slots__ = ("tag", "attrs", "children")

    def __init__(self, tag):
        self.tag = tag
        self.attrs = {}
        self.children = []

    def find_all(self, tag):
        for c in self.children:
            if c.tag == tag:
                yield c
            yield from c.find_all(tag)


_E = re.compile(r"^(\s*)E: ([\w:.-]+)")

# The attribute name is printed fully qualified and the namespace is a URI, so
# it contains both ":" and "/" -- `http://schemas.android.com/apk/res/android:
# versionCode(0x0101021b)=2`. Splitting on a colon finds "http". So the name is
# taken whole, up to the optional resource id, and stored as aapt2 printed it;
# lookups use ANDROID_NS + ":attr" to match.
_A = re.compile(r"^(\s*)A: ([^=]+?)(?:\(0x[0-9a-fA-F]+\))?=(.*)$")


def parse_xmltree(text):
    """Build a tree from `aapt2 dump xmltree` output.

    Indentation is the only structure aapt2 gives, and its steps are not
    uniform (an activity's attributes sit at 12 spaces while its intent-filter
    child sits at 14). So nesting is decided by "strictly greater indent is a
    descendant" rather than by any fixed step size.
    """
    root = Node("#root")
    stack = [(-1, root)]
    for line in text.splitlines():
        m = _E.match(line)
        if m:
            indent = len(m.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = Node(m.group(2))
            stack[-1][1].children.append(node)
            stack.append((indent, node))
            continue
        m = _A.match(line)
        if m:
            indent, key, raw = len(m.group(1)), m.group(2).strip(), m.group(3)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if not stack:
                continue
            value = raw.strip()
            # ="x" (Raw: "x") -> x ;  =true -> true ;  =2 -> 2
            q = re.match(r'^"((?:[^"\\]|\\.)*)"', value)
            value = q.group(1) if q else value
            stack[-1][1].attrs[key] = value
    return root


def cmd_gate1(args):
    """The HOME invariant.

    An APK that has lost its CATEGORY_HOME filter installs perfectly and leaves
    the car with no home screen, recoverable only over ADB with the vehicle
    powered. This is the one defect that must never reach the unit, so it is
    checked on the built artifact rather than trusted from the source manifest.
    """
    tree = parse_xmltree(sys.stdin.read())

    manifest = next(tree.find_all("manifest"), None)
    if manifest is None:
        print("GATE-1: no <manifest> in xmltree", file=sys.stderr)
        return 1

    got_code = manifest.attrs.get(ANDROID_NS + ":versionCode")
    got_name = manifest.attrs.get(ANDROID_NS + ":versionName")
    if got_code != str(args.version_code):
        print("GATE-1: apk versionCode=%s, expected %d" % (got_code, args.version_code),
              file=sys.stderr)
        return 1
    if got_name != args.version_name:
        print("GATE-1: apk versionName=%s, expected %s" % (got_name, args.version_name),
              file=sys.stderr)
        return 1

    for act in tree.find_all("activity"):
        if act.attrs.get(ANDROID_NS + ":name") != args.activity:
            continue
        for filt in act.find_all("intent-filter"):
            cats = set(c.attrs.get(ANDROID_NS + ":name")
                       for c in filt.children if c.tag == "category")
            acts = set(a.attrs.get(ANDROID_NS + ":name")
                       for a in filt.children if a.tag == "action")
            if ("android.intent.category.HOME" in cats
                    and "android.intent.category.DEFAULT" in cats
                    and "android.intent.action.MAIN" in acts):
                print("GATE-1: %s MAIN+HOME+DEFAULT, versionCode=%s versionName=%s"
                      % (args.activity, got_code, got_name))
                return 0
        print("GATE-1: %s exists but has no MAIN+HOME+DEFAULT filter" % args.activity,
              file=sys.stderr)
        return 1

    print("GATE-1: no activity named %s" % args.activity, file=sys.stderr)
    return 1


# -- manifest JSON -----------------------------------------------------------

def cmd_emit(args):
    """Write ota-manifest.json.

    indent and newline are pinned because the detached signature covers the
    exact bytes of this file. Anything that re-serialises it -- a formatter, an
    editor's save-on-open -- invalidates the signature, which is the intended
    behaviour: the device verifies bytes, never a re-parse.
    """
    notes = []
    if args.notes_file:
        with open(args.notes_file, encoding="utf-8") as fh:
            lines = [ln.rstrip("\n") for ln in fh]
        # The pane renders each entry with its own bullet, so prose paragraphs
        # must not become bullets. When the file marks its bullets, only those
        # are taken and the surrounding prose is left for the GitHub release
        # page; when it marks none, every non-empty line is one.
        bullets = [ln.strip()[1:].strip() for ln in lines if ln.strip()[:1] in ("-", "*")]
        notes = bullets if bullets else [ln.strip() for ln in lines if ln.strip()]

    entry = {
        "kind": "apk",
        "packageName": args.package,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "url": args.url,
        "size": args.size,
        "sha256": args.sha256,
        "signerSha256": args.signer.replace(":", "").upper(),
        "minSdk": args.min_sdk,
        "maxSdk": None,
        "isHome": True,
        "mandatory": False,
        "releaseNotes": notes,
    }
    doc = {"schema": 1, "serial": args.serial, "packages": [entry]}
    body = json.dumps(doc, indent=2, ensure_ascii=False, sort_keys=False) + "\n"
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)
    print("emit: serial=%d versionCode=%d %d bytes"
          % (args.serial, args.version_code, len(body.encode("utf-8"))))
    return 0


def cmd_field(args):
    """Print one dotted field, for release.sh to capture. Missing is an error."""
    with open(args.file, encoding="utf-8") as fh:
        doc = json.load(fh)
    cur = doc
    for part in args.path.split("."):
        if part.isdigit() and isinstance(cur, list):
            cur = cur[int(part)]
        elif isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            print("field: no %s in %s" % (args.path, args.file), file=sys.stderr)
            return 1
    print(cur if cur is not None else "")
    return 0


def cmd_gate5(args):
    """The downloaded manifest says what we believe we published.

    docs/04 §10.5: a pipeline reported success at every stage while shipping
    nothing. So this reads the bytes that came back off the public URL -- not
    the ones we wrote -- and checks them against what we intended.
    """
    with open(args.file, encoding="utf-8") as fh:
        doc = json.load(fh)

    problems = []
    if doc.get("schema") != 1:
        problems.append("schema=%r, expected 1" % doc.get("schema"))
    if doc.get("serial") != args.serial:
        problems.append("serial=%r, expected %d" % (doc.get("serial"), args.serial))

    pkgs = doc.get("packages") or []
    entry = None
    for p in pkgs:
        if p.get("packageName") == args.package:
            entry = p
            break
    if entry is None:
        problems.append("no entry for %s" % args.package)
    else:
        for key, want in (("versionCode", args.version_code),
                          ("versionName", args.version_name),
                          ("sha256", args.sha256),
                          ("url", args.url),
                          ("size", args.size)):
            got = entry.get(key)
            if got != want:
                problems.append("%s=%r, expected %r" % (key, got, want))
        want_signer = args.signer.replace(":", "").upper()
        if entry.get("signerSha256") != want_signer:
            problems.append("signerSha256=%r, expected %r"
                            % (entry.get("signerSha256"), want_signer))

    if problems:
        for p in problems:
            print("GATE-5: " + p, file=sys.stderr)
        return 1
    print("GATE-5: downloaded manifest agrees on serial, version, url, size, sha256, signer")
    return 0


def cmd_gate3(args):
    """The OTA public key shipped, and it is the current one.

    Not a path check. AGP's release pipeline runs `aapt2 optimize
    --shorten-resource-paths`, so res/raw/ota_public_key.der is rewritten to
    something like res/j5.der before packaging. The rename is transparent to
    openRawResource() but fatal to any check that looks for the old name -- and
    a check that silently stops matching is exactly the docs/04 §10.5 failure.

    So this hashes every entry and asserts the expected bytes are in there.
    That holds whatever the path becomes, and it also proves the key is the
    CURRENT one rather than a stale copy from a previous keygen.
    """
    import zipfile

    want = args.sha256.lower()
    found = []
    with zipfile.ZipFile(args.apk) as zf:
        for info in zf.infolist():
            if info.is_dir() or info.file_size != args.size:
                continue
            h = hashlib.sha256()
            with zf.open(info) as fh:
                for chunk in iter(lambda: fh.read(1 << 16), b""):
                    h.update(chunk)
            if h.hexdigest() == want:
                found.append(info.filename)

    if not found:
        print("GATE-3: no entry in %s has sha256 %s -- the OTA public key did not "
              "ship, or it is a stale copy" % (args.apk, want), file=sys.stderr)
        return 1
    print("GATE-3: OTA public key present as %s (%d bytes)" % (", ".join(found), args.size))
    return 0


def cmd_sha256(args):
    h = hashlib.sha256()
    with open(args.file, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    print(h.hexdigest())
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("gate1", help="check the HOME invariant on stdin's xmltree")
    p.add_argument("--activity", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--version-name", required=True)
    p.set_defaults(fn=cmd_gate1)

    p = sub.add_parser("emit", help="write ota-manifest.json")
    p.add_argument("--out", required=True)
    p.add_argument("--serial", type=int, required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--url", required=True)
    p.add_argument("--size", type=int, required=True)
    p.add_argument("--sha256", required=True)
    p.add_argument("--signer", required=True)
    p.add_argument("--min-sdk", type=int, required=True)
    p.add_argument("--notes-file")
    p.set_defaults(fn=cmd_emit)

    p = sub.add_parser("field", help="print one dotted field")
    p.add_argument("file")
    p.add_argument("path")
    p.set_defaults(fn=cmd_field)

    p = sub.add_parser("gate5", help="check a downloaded manifest against intent")
    p.add_argument("file")
    p.add_argument("--serial", type=int, required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--url", required=True)
    p.add_argument("--size", type=int, required=True)
    p.add_argument("--sha256", required=True)
    p.add_argument("--signer", required=True)
    p.set_defaults(fn=cmd_gate5)

    p = sub.add_parser("gate3", help="the OTA public key shipped inside an APK")
    p.add_argument("apk")
    p.add_argument("--sha256", required=True)
    p.add_argument("--size", type=int, required=True)
    p.set_defaults(fn=cmd_gate3)

    p = sub.add_parser("sha256", help="hex sha256 of a file")
    p.add_argument("file")
    p.set_defaults(fn=cmd_sha256)

    args = ap.parse_args()
    sys.exit(args.fn(args))


if __name__ == "__main__":
    main()
