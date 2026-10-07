#!/usr/bin/env python3
"""Check that every Minecraft-targeted @Mixin, @Inject, @Shadow, @Accessor and @Invoker still resolves.

A mixin whose target drifted compiles fine and fails only when the game starts, which is the worst place to
find out — and the two Minecraft versions this project targets drift from each other. Run from the repository
root:

    tools/check_mixin_targets.py <minecraft-merged-deobf.jar>

Mixin sources name their targets with the simple name they imported, so imports are resolved first. Mixins
aimed at other mods (Sodium, Vitrail, AsyncParticles) are skipped by name: their targets are not in Minecraft.
Names are checked, not descriptors — a renamed member is the failure mode this exists for, and a descriptor
check would need a Java signature parser.
"""
import json
import os
import re
import subprocess
import sys

SRC = "src/minecraft/java"
CONFIGS = [
    "src/minecraft/resources/lavaflow.mixins.json",
    "src/minecraft/resources/lavaflow-debug.mixins.json",
    "src/minecraft/resources/lavaflow-sodium.mixins.json",
]
MIXIN_HEADER = re.compile(r"@Mixin\(\s*([A-Za-z0-9_.$]+)\s*\.class")
TARGETS_ATTR = re.compile(r"@Mixin\(\s*targets\s*=\s*\"([^\"]+)\"")
MEMBER = re.compile(
    r"@(Inject|Redirect|ModifyArg|ModifyVariable|ModifyExpressionValue|WrapOperation|WrapWithCondition)"
    r"\(([^)]*)\)", re.S)
METHOD_ATTR = re.compile(r"method\s*=\s*\"([^\"]+)\"")
SHADOW_FIELD = re.compile(r"@Shadow[^\n]*\n\s*(?:@\w+[^\n]*\n\s*)*[^\n;]*?\b([A-Za-z_$][A-Za-z0-9_$]*)\s*;")
ACCESSOR = re.compile(r"@(Accessor|Invoker)\(\s*(?:value\s*=\s*)?\"([^\"]+)\"")
# The declaration that follows an @Accessor/@Invoker, used to see whether the mixin's own method is static.
# A static target needs a static mixin method and the other way round; the wrong form compiles and then fails
# when the mixin is applied, which is a startup error.
ACCESSOR_DECL = re.compile(r"@(Accessor|Invoker)\(\s*(?:value\s*=\s*)?\"([^\"]+)\"\s*\)([^;]{0,200}?)[;)]")
IMPORT = re.compile(r"^import\s+(?:static\s+)?([A-Za-z0-9_.$]+)\s*;", re.M)


def config_names():
    out = set()
    for path in CONFIGS:
        if not os.path.exists(path):
            continue
        cfg = json.load(open(path))
        for bucket in ("mixins", "client", "server"):
            for entry in cfg.get(bucket, []):
                out.add(entry.split("$")[0])
    return out


def resolve(simple, imports):
    if "." in simple:
        return simple
    for imported in imports:
        if imported.endswith("." + simple):
            return imported
    return simple


def members_of(jar, class_name):
    result = subprocess.run(["javap", "-p", "-classpath", jar, class_name],
                            capture_output=True, text=True, timeout=120)
    text = result.stdout
    # Judged by the exit status, not by grepping the output: a class that exists can still mention the word
    # "error" in a method of its own (Minecraft does), and that is what a text check would trip over.
    if result.returncode != 0 or not text.strip():
        return None
    names = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("Compiled from", "public class", "class ", "public interface",
                                        "interface ", "abstract class", "public abstract class", "}")):
            continue
        is_static = re.search(r"\bstatic\b", line.split("(")[0]) is not None
        line = re.sub(r"^(?:public|private|protected|static|final|abstract|native|synchronized|transient"
                      r"|volatile|\s)+", "", line)
        line = line.split("(")[0].split("=")[0].strip()
        if line:
            names[line.split()[-1].rstrip(";")] = is_static
    return names


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    jar = sys.argv[1]
    declared = config_names()
    problems, checked, skipped = [], 0, []
    for root, _dirs, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java") or name[:-5] not in declared:
                continue
            path = os.path.join(root, name)
            text = open(path).read()
            imports = IMPORT.findall(text)
            header = MIXIN_HEADER.search(text)
            external = TARGETS_ATTR.search(text)
            if not header:
                if external:
                    skipped.append(f"{name} -> {external.group(1)} (not Minecraft)")
                else:
                    problems.append(f"{name}: no @Mixin target found")
                continue
            target = resolve(header.group(1), imports)
            members = members_of(jar, target)
            if members is None:
                skipped.append(f"{name} -> {target} (not in Minecraft)")
                continue
            checked += 1
            for kind, args in MEMBER.findall(text):
                attr = METHOD_ATTR.search(args)
                if not attr:
                    continue
                method = attr.group(1).split("(")[0].split(";")[0].strip()
                if method not in members:
                    problems.append(f"{name}: @{kind} target {target}.{method} not found")
            for kind, member, decl in ACCESSOR_DECL.findall(text):
                if member not in members:
                    problems.append(f"{name}: @{kind} target {target}.{member} not found")
                    continue
                mixin_static = re.search(r"\bstatic\b", decl) is not None
                if mixin_static != members[member]:
                    problems.append(f"{name}: @{kind} target {target}.{member} is "
                                    f"{'static' if members[member] else 'an instance member'} but the mixin "
                                    f"declares it {'static' if mixin_static else 'instance'}")
            for field in SHADOW_FIELD.findall(text):
                if field not in members:
                    problems.append(f"{name}: @Shadow field {target}.{field} not found")
    for problem in problems:
        print("::error::" + problem)
    for note in skipped:
        print(f"skipped {note}")
    print(f"checked {checked} Minecraft mixins against {os.path.basename(jar)}: "
          f"{'OK' if not problems else str(len(problems)) + ' problem(s)'}, {len(skipped)} skipped")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
