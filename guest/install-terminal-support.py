#!/usr/bin/env python3
"""Install terminal support in a running ASL guest. Input is a JSON bundle."""
import json
import os
import pathlib
import pwd
import re
import subprocess
import sys

payload=json.load(sys.stdin)
user=pwd.getpwuid(1000)
home_path=pathlib.Path(user.pw_dir)


def owned(path, content, mode=None):
    if mode is None:mode=(path.stat().st_mode & 0o777) if path.exists() else 0o644
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(content);path.chmod(mode);os.chown(path,user.pw_uid,user.pw_gid)


def managed(path, label, content):
    start='# BEGIN '+label;end='# END '+label
    old=path.read_text() if path.exists() else ''
    block=start+'\n'+content.rstrip()+'\n'+end
    if start in old:
        if end not in old:raise RuntimeError('Incomplete managed block in '+str(path))
        new=re.sub(re.escape(start)+r'.*?'+re.escape(end),lambda _:block,old,flags=re.S)
    else:new=old.rstrip()+'\n\n'+block+'\n'
    owned(path,new)


agent=pathlib.Path('/usr/local/lib/asl/asl_guest_agent.py')
if 'asltree' in payload:
    directory=agent.parent/'asltree';directory.mkdir(exist_ok=True)
    for name,content in payload['asltree'].items():
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*\.py',name):raise ValueError('Invalid ASLTree module')
        compile(content,name,'exec');target=directory/name;target.write_text(content);target.chmod(0o644)
    cli=pathlib.Path('/usr/local/bin/asltree')
    cli.write_text('#!/bin/sh\nPYTHONPATH=/usr/local/lib/asl exec python3 -m asltree "$@"\n');cli.chmod(0o755)
compile(payload['agent'],str(agent),'exec')
temporary=agent.with_suffix('.new');temporary.write_text(payload['agent']);temporary.chmod(0o755);temporary.replace(agent)
for name in ('asl_pty.py','asl_android.py'):
    if name in payload:
        target=agent.parent/name;compile(payload[name],str(target),'exec');target.write_text(payload[name]);target.chmod(0o755)
for directory in (home_path/'.config',home_path/'.config/asl'):
    directory.mkdir(exist_ok=True);os.chown(directory,user.pw_uid,user.pw_gid)
owned(home_path/'.config/asl/terminal-profile.sh',payload['profile'])
managed(home_path/'.bash_profile','ASL TERMINAL','[ -f "$HOME/.config/asl/terminal-profile.sh" ] && . "$HOME/.config/asl/terminal-profile.sh"')
managed(home_path/'.tmux.conf','ASL TERMINAL',payload['tmux'])
python_alias=pathlib.Path('/usr/local/bin/python')
if not python_alias.exists():python_alias.symlink_to('/usr/bin/python3')
launcher=pathlib.Path('/usr/local/bin/codex')
if launcher.is_symlink() and launcher.resolve()==pathlib.Path('/opt/codex/bin/codex') or launcher.is_file() and launcher.read_bytes().startswith(b'#!/bin/sh\n# ASL'):
    wrapper=launcher.with_name('.asl-codex-new')
    wrapper.write_text('#!/bin/sh\n# ASL terminal defaults\nexec /opt/codex/bin/codex -c tui.animations=false -c \'tui.alternate_screen="never"\' "$@"\n')
    wrapper.chmod(0o755);wrapper.replace(launcher)
if not payload.get('offline',False):subprocess.run(['systemctl','restart','asl-agent'],check=True)
version=subprocess.check_output(['tmux','-V'],text=True).strip()
print(json.dumps({'installed':True,'user':user.pw_name,'terminal_backend':version}))
