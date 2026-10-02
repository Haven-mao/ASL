#!/usr/bin/env bash
set -euo pipefail
ASL_RAW=${1:?raw image}
ASL_IMAGE=${2:?output image directory}
ASL_BUNDLE=${3:?output .asl bundle}
e2fsck -fn "$ASL_RAW"
qemu-img convert -c -f raw -O qcow2 "$ASL_RAW" "$ASL_IMAGE/rootfs.qcow2"
qemu-img check "$ASL_IMAGE/rootfs.qcow2"
python3 - "$ASL_IMAGE" "$ASL_BUNDLE" <<'PY'
import hashlib,json,pathlib,sys,zipfile
image,bundle=map(pathlib.Path,sys.argv[1:])
files=['kernel','initrd','config.json','rootfs.qcow2']
manifest={'format':1,'version':'0.5.0','name':'ASL Debian 12 ARM64','codex':'0.160.0','sha256':{}}
for name in files:
    digest=hashlib.sha256()
    with (image/name).open('rb') as stream:
        for data in iter(lambda:stream.read(4*1024*1024),b''):digest.update(data)
    manifest['sha256'][name]=digest.hexdigest()
(image/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(image/'READY').write_text('ASL 0.5.0\n')
with zipfile.ZipFile(bundle,'w',compression=zipfile.ZIP_STORED) as archive:
    for name in files+['manifest.json']:archive.write(image/name,name)
print(json.dumps({'system_bundle_bytes':bundle.stat().st_size,'root_disk_bytes':(image/'rootfs.qcow2').stat().st_size}))
PY
