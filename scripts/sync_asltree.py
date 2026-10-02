#!/usr/bin/env python3
"""Stage the pinned ASLTree package in the guest bundle (generated, not committed)."""
import argparse
import json
import pathlib
import shutil
import subprocess

root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--source',type=pathlib.Path,default=root/'external/ASLTree');args=parser.parse_args()
package=args.source/'asltree'
if not (package/'engine.py').is_file():raise SystemExit('Initialize external/ASLTree or pass --source /path/to/ASLTree')
target=root/'guest/asltree';target.mkdir(exist_ok=True)
names={file.name for file in package.glob('*.py')}
for old in target.glob('*.py'):
    if old.name not in names:old.unlink()
for file in package.glob('*.py'):shutil.copy2(file,target/file.name)
print(json.dumps({'staged':'asltree','files':len(names)}))
