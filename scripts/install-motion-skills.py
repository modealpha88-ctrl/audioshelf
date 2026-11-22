#!/usr/bin/env python3
"""Install the 19 pinned iArt packs into Codex's project skill directory."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    script = Path(__file__).resolve()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path,
                        default=script.parent.parent / ".agents" / "skills")
    destination = parser.parse_args().destination.resolve()
    packs = [line.split() for line in script.with_name("motion-skills.lock").read_text().splitlines()]
    if len(packs) != 19 or len({pack for pack, _ in packs}) != 19:
        raise SystemExit("Expected 19 distinct packs in motion-skills.lock")
    destination.mkdir(parents=True, exist_ok=True)
    skills = {}
    with tempfile.TemporaryDirectory(prefix="iart-motion-") as staging:
        for pack, revision in packs:
            checkout = Path(staging) / pack
            subprocess.run(["git", "init", "-q", checkout], check=True)
            subprocess.run(["git", "-C", checkout, "fetch", "-q", "--depth=1",
                            f"https://github.com/iart-ai/{pack}.git", revision], check=True)
            subprocess.run(["git", "-C", checkout, "checkout", "-q", "--detach", "FETCH_HEAD"],
                           check=True)
            manifests = sorted((checkout / "skills").glob("*/SKILL.md"))
            if not manifests:
                raise SystemExit(f"No skills in {pack}")
            for manifest in manifests:
                # Both packs ship a lower-thirds skill with different guidance.
                name = ("tiktok-lower-thirds" if pack == "tiktok-video-skills"
                        and manifest.parent.name == "lower-thirds" else manifest.parent.name)
                if name != manifest.parent.name:
                    text = manifest.read_text(encoding="utf-8")
                    manifest.write_text(text.replace("\nname: lower-thirds\n",
                                                     "\nname: tiktok-lower-thirds\n", 1),
                                        encoding="utf-8")
                if name in skills:
                    raise SystemExit(f"Duplicate skill: {name}")
                target = destination / name
                if target.exists() or target.is_symlink():
                    installed = {p.relative_to(target): p.read_bytes()
                                 for p in target.rglob("*") if p.is_file()}
                    expected = {p.relative_to(manifest.parent): p.read_bytes()
                                for p in manifest.parent.rglob("*") if p.is_file()}
                    if target.is_symlink() or not installed or installed != expected:
                        raise SystemExit(f"Conflicting installation: {target}")
                skills[name] = manifest.parent
        for name, source in skills.items():
            if not (destination / name).exists():
                shutil.copytree(source, destination / name)
    print(f"Installed {len(skills)} skills from {len(packs)} packs into {destination}")


if __name__ == "__main__":
    main()
