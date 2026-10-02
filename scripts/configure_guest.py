import json
import os
import pathlib
import shutil
import subprocess
import tarfile
import argparse
import hashlib

parser=argparse.ArgumentParser()
parser.add_argument('--root',required=True,type=pathlib.Path)
parser.add_argument('--codex-archive',required=True,type=pathlib.Path)
parser.add_argument('--output',required=True,type=pathlib.Path)
args=parser.parse_args()
source=pathlib.Path(__file__).resolve().parents[1]
root=args.root
expected='7f0fe42ff22ecfa3a47bc4a34f5b22c4218b431a4ec0aba51c7d98299f07900c'
if hashlib.sha256(args.codex_archive.read_bytes()).hexdigest()!=expected:raise RuntimeError('Codex archive SHA256 mismatch')
assert (root/'.asl-packages-ready').exists()


def write(name,text,mode=0o644):
    target=root/name.lstrip('/')
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text)
    target.chmod(mode)


def chroot(*args):
    subprocess.run(['chroot',str(root),*args],check=True)


write('/etc/hostname','asl\n')
write('/etc/hosts','127.0.0.1 localhost\n127.0.1.1 asl\n::1 localhost ip6-localhost\n')
write('/etc/fstab','LABEL=asl-root / ext4 defaults,noatime 0 1\n')
write('/etc/resolv.conf','nameserver 223.5.5.5\nnameserver 1.1.1.1\n')
write('/etc/systemd/network/10-asl.network','[Match]\nName=eth* en*\n[Network]\nDHCP=ipv4\nIPv6AcceptRA=no\n[DHCPv4]\nUseDNS=no\n')
write('/etc/systemd/system/serial-getty@ttyAMA0.service.d/autologin.conf','[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin asl --noclear --keep-baud 115200,38400,9600 - xterm-256color\n')
if not (root/'home/asl').exists():
    chroot('useradd','-m','-u','1000','-s','/bin/bash','-G','sudo,docker','asl')
write('/etc/sudoers.d/asl','asl ALL=(ALL) NOPASSWD: ALL\n',0o440)
write('/home/asl/.ssh/authorized_keys','',0o600)
(root/'home/asl/.ssh').chmod(0o700)
write('/etc/ssh/sshd_config.d/asl.conf','PasswordAuthentication no\nPermitRootLogin no\nHostKey /etc/ssh/ssh_host_ed25519_key\n')
for key in (root/'etc/ssh').glob('ssh_host_*'):
    if key.is_file():key.unlink()
write('/etc/machine-id','')
dbus_id=root/'var/lib/dbus/machine-id'
if dbus_id.exists() and not dbus_id.is_symlink():dbus_id.unlink()
write('/etc/issue','ASL Debian GNU/Linux 12 \\l\n')
write('/etc/motd','ASL Linux ready\nAndroid Subsystem for Linux · Developer Preview 0.5.0\n\nRun asl-help for commands.\n')
write('/home/asl/.bash_profile','export LANG=C.UTF-8\nexport TERM=xterm-256color\nexport PATH=/opt/codex/codex-path:$PATH\n[ -f ~/.bashrc ] && . ~/.bashrc\ncd ~/workspace\n')
write('/home/asl/workspace/AGENTS.md',
      '# ASL workspace\n\nThis Linux VM runs locally on the user\'s Android tablet. The android MCP server controls Android apps through the ASL accessibility bridge. Inspect the current UI before acting. Prefer node IDs for clicks and input. If using screenshots, pass screenshot_id with image coordinates so ASL scales them automatically. Verify the result after each action. Ask the user before sending messages, purchasing, deleting user data, or other externally visible or irreversible actions. Do not bypass Android permission dialogs or secure-screen restrictions.\n')
write('/home/asl/.codex/config.toml','[mcp_servers.android]\ncommand = "python3"\nargs = ["/usr/local/lib/asl/asl_android.py", "mcp"]\n')
write('/usr/local/bin/asl-help',"#!/bin/sh\ncat <<'EOF'\nASL Terminal\n\n  codex login --device-auth   登录你自己的 Codex 账号\n  cd ~/workspace && codex     启动 Codex，使用 android MCP 工具\n  asl-android status         查看 Android 控制连接\n  asl-android apps           列出 Android 应用\n  asl-android tree           读取当前 Android 界面\n  asl-android screenshot     保存 Android 截图\n  docker run hello-world     检查 Docker\n  sudo poweroff              正常关闭 Linux\n\nAndroid 控制需要在平板无障碍设置中启用 ASL Android 控制。\nEOF\n",0o755)
for name in ('asl_android.py','asl_guest_agent.py','asl_pty.py'):
    dest=root/'usr/local/lib/asl'/name;dest.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(source/'guest'/name,dest);dest.chmod(0o755)
link=root/'usr/local/bin/asl-android'
if not link.exists() and not link.is_symlink():link.symlink_to('/usr/local/lib/asl/asl_android.py')
write('/etc/systemd/system/asl-init.service','[Unit]\nDescription=ASL guest configuration\nAfter=systemd-udev-trigger.service\nBefore=ssh.service asl-agent.service\n[Service]\nType=oneshot\nExecStart=/usr/bin/python3 /usr/local/lib/asl/asl_guest_agent.py --init\nRemainAfterExit=yes\n[Install]\nWantedBy=multi-user.target\n')
write('/etc/systemd/system/asl-agent.service','[Unit]\nDescription=ASL terminal size and lifecycle agent\nAfter=asl-init.service network.target\nRequires=asl-init.service\n[Service]\nExecStart=/usr/bin/python3 /usr/local/lib/asl/asl_guest_agent.py\nRestart=on-failure\nRestartSec=3\n[Install]\nWantedBy=multi-user.target\n')
write('/etc/systemd/system/ssh.service.d/asl.conf','[Unit]\nAfter=asl-init.service\nRequires=asl-init.service\n')
write('/etc/docker/daemon.json','{"log-driver":"local","log-opts":{"max-size":"10m","max-file":"2"}}\n')
codex=root/'opt/codex'
codex.mkdir(parents=True,exist_ok=True)
with tarfile.open(args.codex_archive) as archive:
    archive.extractall(codex)
link=root/'usr/local/bin/codex'
if not link.exists() and not link.is_symlink():link.symlink_to('/opt/codex/bin/codex')
chroot('chown','-R','asl:asl','/home/asl')
installer=(source/'guest/install-terminal-support.py').read_text()
payload={'offline':True,'agent':(source/'guest/asl_guest_agent.py').read_text(),'profile':(source/'guest/terminal-profile.sh').read_text(),'tmux':(source/'guest/tmux.conf').read_text(),'asl_pty.py':(source/'guest/asl_pty.py').read_text(),'asl_android.py':(source/'guest/asl_android.py').read_text()}
payload['asltree']={file.name:file.read_text() for file in (source/'guest/asltree').glob('*.py')}
if not payload['asltree']:raise RuntimeError('Run scripts/sync_asltree.py before building the guest')
subprocess.run(['chroot',str(root),'/usr/bin/python3','-c',installer],input=json.dumps(payload),text=True,check=True)
chroot('systemctl','enable','systemd-networkd','serial-getty@ttyAMA0','asl-init','asl-agent','ssh','docker')
chroot('systemctl','disable','systemd-networkd-wait-online.service')
chroot('apt-get','clean')
chroot('/usr/local/bin/codex','--version')
chroot('python3','-m','py_compile','/usr/local/lib/asl/asl_android.py','/usr/local/lib/asl/asl_guest_agent.py')
image=args.output;image.mkdir(parents=True,exist_ok=True)
shutil.copy2(next((root/'boot').glob('vmlinuz-*')),image/'kernel')
shutil.copy2(next((root/'boot').glob('initrd.img-*')),image/'initrd')
(image/'config.json').write_text(json.dumps({'format':1,'distribution':'Debian 12','architecture':'arm64','root':'LABEL=asl-root','codex':'0.160.0'})+'\n')
print('Guest configured with Codex and Android MCP tools',flush=True)
