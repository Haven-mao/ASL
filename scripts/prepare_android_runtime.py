#!/usr/bin/env python3
"""Bundle Android/Bionic QEMU and only its reachable ELF dependencies.

Uses Termux's Android builds; does not require the Termux app or its data folder.
Package SHA-256 hashes are verified before extraction. Run on Linux with
Python 3, dpkg-deb and patchelf.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import pathlib
import re
import shutil
import struct
import subprocess
import tarfile
import urllib.request

BASE='https://packages-cf.termux.dev/apt/termux-main/'
SYSTEM={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libjnigraphics.so','libOpenSLES.so','libEGL.so','libGLESv2.so','libGLESv3.so'}
SKIP={'termux-tools','termux-services','termux-keyring','command-not-found','resolv-conf','ca-certificates'}


def shorten_library_names(path, aliases):
    """Rename versioned .so names in place, preserving Android ELF segment layout."""
    data=bytearray(path.read_bytes())
    if data[:6]!=b'\x7fELF\x02\x01':raise RuntimeError('Expected little-endian ELF64')
    table=struct.unpack_from('<Q',data,40)[0]
    entry_size,count,string_index=struct.unpack_from('<HHH',data,58)
    sections=[struct.unpack_from('<IIQQQQIIQQ',data,table+i*entry_size) for i in range(count)]
    names_section=sections[string_index];names=data[names_section[4]:names_section[4]+names_section[5]]
    named={bytes(names[s[0]:]).split(b'\0',1)[0].decode():s for s in sections}
    strings=named['.dynstr'];base,size=strings[4],strings[5]
    indices=set();dynamic=named['.dynamic']
    for offset in range(dynamic[4],dynamic[4]+dynamic[5],16):
        tag,value=struct.unpack_from('<qQ',data,offset)
        if tag in (1,14):indices.add(value)  # DT_NEEDED and DT_SONAME
    versions=named.get('.gnu.version_r')
    if versions:
        cursor=versions[4]
        while cursor<versions[4]+versions[5]:
            version,number,file_index,aux,next_entry=struct.unpack_from('<HHIII',data,cursor)
            indices.add(file_index)
            if not next_entry:break
            cursor+=next_entry
    changes=[]
    for index in indices:
        if index>=size:raise RuntimeError('Invalid ELF string index')
        old=bytes(data[base+index:base+size]).split(b'\0',1)[0]
        new=aliases.get(old.decode())
        if new is None or new.encode()==old:continue
        new=new.encode()
        if len(new)>len(old):raise RuntimeError('Library alias must not grow the ELF string')
        if any(index<other<=index+len(old) for other in indices):raise RuntimeError('Overlapping ELF library names')
        changes.append((base+index,len(old),new))
    for offset,length,value in changes:data[offset:offset+length]=value+b'\0'*(length-len(value))
    if changes:path.write_bytes(data)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--index',type=pathlib.Path,required=True);parser.add_argument('--cache',type=pathlib.Path,required=True);parser.add_argument('--out',type=pathlib.Path,required=True);parser.add_argument('--plan-only',action='store_true')
    args=parser.parse_args();args.cache.mkdir(parents=True,exist_ok=True);args.out.mkdir(parents=True,exist_ok=True)
    packages={}
    for block in gzip.open(args.index,'rt').read().split('\n\n'):
        package=dict(line.split(': ',1) for line in block.splitlines() if ': ' in line and not line.startswith(' '))
        if 'Package' in package:packages[package['Package']]=package
    selected={}
    def include(name):
        if name in selected or name in SKIP:return
        package=packages[name];selected[name]=package
        for dependency in package.get('Depends','').split(','):
            if not dependency.strip():continue
            alternatives=[re.split(r'[ (]',part.strip())[0] for part in dependency.split('|')]
            target=next((part for part in alternatives if part in packages),None)
            if target:include(target)
    include('qemu-system-aarch64-headless');include('strace')
    include('libandroid-support');include('libc++')
    (args.cache/'packages.json').write_text(json.dumps(list(selected.values()),indent=2)+'\n')
    if args.plan_only:return
    print(f'Downloading {len(selected)} Android packages',flush=True)
    def download(package):
        path=args.cache/pathlib.PurePosixPath(package['Filename']).name.replace(':','_')
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=package['SHA256']:
            partial=path.with_suffix('.partial')
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(BASE+package['Filename'],timeout=60) as source,partial.open('wb') as target:shutil.copyfileobj(source,target)
                    if hashlib.sha256(partial.read_bytes()).hexdigest()!=package['SHA256']:raise RuntimeError('Package hash mismatch: '+package['Package'])
                    partial.replace(path);break
                except Exception:
                    if attempt==2:raise
        return package,path
    root=args.cache/'root';root.mkdir(exist_ok=True)
    records=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for package,path in pool.map(download,selected.values()):
            receipt=root/('.elf-package-'+package['Package'])
            if not receipt.exists() or receipt.read_text()!=package['SHA256']:
                process=subprocess.Popen(['dpkg-deb','--fsys-tarfile',str(path)],stdout=subprocess.PIPE)
                with tarfile.open(fileobj=process.stdout,mode='r|') as archive:
                    for member in archive:
                        name=member.name.removeprefix('./')
                        pure=pathlib.PurePosixPath(name)
                        if pure.is_absolute() or '..' in pure.parts:raise RuntimeError('Unsafe package path')
                        if not member.isfile():continue
                        if not (name.startswith('data/data/com.termux/files/usr/lib/') or name in ('data/data/com.termux/files/usr/bin/qemu-system-aarch64','data/data/com.termux/files/usr/bin/strace')):continue
                        source=archive.extractfile(member);magic=source.read(4)
                        if magic!=b'\x7fELF':continue
                        target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
                        with target.open('wb') as output:output.write(magic);shutil.copyfileobj(source,output)
                        target.chmod(0o755)
                process.stdout.read();process.stdout.close()
                if process.wait()!=0:raise RuntimeError('Package extraction failed: '+package['Package'])
                receipt.write_text(package['SHA256'])
            records.append({key:package.get(key) for key in ('Package','Version','Filename','SHA256','Homepage')})
    prefix=root/'data/data/com.termux/files/usr'
    libraries={}
    for path in (prefix/'lib').rglob('*'):
        if not path.is_file() or path.is_symlink():continue
        with path.open('rb') as stream:
            if stream.read(4)!=b'\x7fELF':continue
        result=subprocess.run(['patchelf','--print-soname',str(path)],text=True,capture_output=True)
        soname=result.stdout.strip()
        libraries[path.name]=path
        if soname:libraries[soname]=path
    emitted={};replacements={}
    def bundle(path,name=None):
        path=path.resolve()
        if path in emitted:return emitted[path]
        soname=subprocess.run(['patchelf','--print-soname',str(path)],text=True,capture_output=True).stdout.strip()
        target_name=name or pathlib.PurePosixPath(soname or path.name).name.split('.so')[0]+'.so'
        if not target_name.startswith('lib'):target_name='lib'+target_name
        destination=args.out/target_name;shutil.copy2(path,destination);emitted[path]=target_name
        if soname:replacements[soname]=target_name
        aliases={soname:target_name} if soname else {}
        dependencies=subprocess.check_output(['patchelf','--print-needed',str(path)],text=True).splitlines()
        for dependency in dependencies:
            base=pathlib.PurePosixPath(dependency).name
            if base in SYSTEM:continue
            dependency_path=libraries.get(dependency) or libraries.get(base)
            if dependency_path is None:raise RuntimeError(f'Missing {dependency} required by {path.name}')
            bundled=bundle(dependency_path)
            aliases[dependency]=bundled
        shorten_library_names(destination,aliases)
        # Preserve Android ELF version tables and GNU property notes exactly.
        # LD_LIBRARY_PATH supplies the installed APK directory at execution time.
        destination.chmod(0o755)
        return target_name
    bundle(prefix/'bin/qemu-system-aarch64','libqemu-system-aarch64.so')
    bundle(prefix/'bin/strace','libasl-strace.so')
    manifest={'architecture':'aarch64','libc':'Android Bionic','repository':BASE,'packages':records,'binaries':[]}
    for name in sorted(emitted.values()):manifest['binaries'].append({'name':name,'sha256':hashlib.sha256((args.out/name).read_bytes()).hexdigest()})
    (args.out.parent/'runtime-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Prepared {len(emitted)} Android ELF files',flush=True)


if __name__=='__main__':main()
