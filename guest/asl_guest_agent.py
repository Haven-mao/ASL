#!/usr/bin/env python3
"""Guest initialization, serial terminal sizing, and authenticated power-off."""
import fcntl
import hmac
import http.server
import json
import os
import pathlib
import pwd
import re
import socket
import struct
import subprocess
import sys
import termios
import threading
import time
import urllib.request
from asl_pty import serve_terminal,session_operation

CONFIG = pathlib.Path('/etc/asl/android-bridge.json')


def tmux(*arguments):
    user=pwd.getpwuid(1000)
    env=os.environ.copy();env.update(HOME=user.pw_dir,USER=user.pw_name,LOGNAME=user.pw_name)
    result=subprocess.run(['/usr/bin/tmux','-L','asl',*arguments],cwd=user.pw_dir,env=env,
                          user=user.pw_uid,group=user.pw_gid,extra_groups=os.getgrouplist(user.pw_name,user.pw_gid),text=True,capture_output=True,timeout=5)
    if result.returncode:
        raise RuntimeError('Linux terminal sessions are not ready yet')
    return result.stdout


def terminal_windows():
    rows=tmux('list-windows','-t','main','-F','#{window_id}\t#{window_index}\t#{window_active}\t#{window_name}')
    windows=[]
    for row in rows.splitlines():
        parts=row.split('\t',3)
        if len(parts)==4 and re.fullmatch(r'@\d+',parts[0]):
            windows.append({'id':parts[0],'index':int(parts[1]),'active':parts[2]=='1','name':parts[3][:80]})
    return windows


def initialize():
    subprocess.run(['modprobe', 'qemu_fw_cfg'], check=False)
    firmware = pathlib.Path('/sys/firmware/qemu_fw_cfg/by_name/opt/asl/bridge/raw')
    for attempt in range(50):
        if firmware.is_file():
            break
        time.sleep(0.2)
    config = json.loads(firmware.read_bytes().rstrip(b'\0'))
    if config.get('url') != 'http://10.0.2.2:8765' or len(config.get('token', '')) != 64:
        raise RuntimeError('Unexpected bridge configuration')
    CONFIG.parent.mkdir(exist_ok=True)
    CONFIG.write_text(json.dumps(config))
    CONFIG.chmod(0o600)
    os.chown(CONFIG, 1000, 1000)
    key = pathlib.Path('/etc/ssh/ssh_host_ed25519_key')
    if not key.exists():
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(key)], check=True)


def run():
    config = json.loads(CONFIG.read_text())
    token = 'Bearer ' + config['token']
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def sync_size():
        previous = None
        while True:
            try:
                request = urllib.request.Request(config['url'] + '/v1/status', data=b'{}', headers={'Authorization': token, 'Content-Type': 'application/json'})
                with opener.open(request, timeout=4) as response:
                    status = json.load(response)
                size = (int(status.get('terminal_rows', 40)), int(status.get('terminal_columns', 120)))
                if size != previous and 5 <= size[0] <= 300 and 20 <= size[1] <= 500:
                    fd = os.open('/dev/ttyAMA0', os.O_RDWR | os.O_NOCTTY)
                    try:
                        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', *size, 0, 0))
                    finally:
                        os.close(fd)
                    previous = size
            except Exception:
                pass
            time.sleep(2)

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), token):
                self.send_error(401)
                return
            if self.path not in ('/health', '/shutdown', '/terminals', '/terminals/new', '/terminals/select','/pty','/pty/sessions','/pty/close','/memory/catalog'):
                self.send_error(404)
                return
            try:
                length=int(self.headers.get('Content-Length','0'))
                if length<0 or length>65536:raise ValueError('Request body too large')
                body=json.loads(self.rfile.read(length) or b'{}')
                if self.path=='/pty':serve_terminal(self,body);return
                response={'ok':True,'guest':'ASL Debian'}
                if self.path=='/memory/catalog':
                    from asltree.store import Store
                    response.update(Store.read_catalog(pathlib.Path(pwd.getpwuid(1000).pw_dir)/'.local/share/asltree/operations.sqlite3'))
                if self.path.startswith('/pty/'):response.update(session_operation(self.path,body))
                if self.path.startswith('/terminals'):
                    if self.path=='/terminals/new':
                        tmux('new-window','-t','main','-c','#{pane_current_path}')
                    elif self.path=='/terminals/select':
                        window=body.get('id')
                        if window not in {item['id'] for item in terminal_windows()}:raise ValueError('Unknown terminal')
                        tmux('select-window','-t',window)
                    response['windows']=terminal_windows()
                data=json.dumps(response).encode()
                self.send_response(202 if self.path=='/shutdown' else 200)
                self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(data)))
                self.end_headers();self.wfile.write(data);self.wfile.flush()
            except (ValueError,RuntimeError,subprocess.TimeoutExpired) as error:
                data=json.dumps({'error':str(error)}).encode()
                self.send_response(409);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)))
                self.end_headers();self.wfile.write(data);return
            if self.path == '/shutdown':
                subprocess.Popen(['systemctl', 'poweroff'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    threading.Thread(target=sync_size, daemon=True).start()
    class Server(http.server.ThreadingHTTPServer):
        daemon_threads=True
        def get_request(self):
            connection,address=super().get_request();connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1);return connection,address
    Server(('0.0.0.0', 2020), Handler).serve_forever()


if __name__ == '__main__':
    initialize() if '--init' in sys.argv else run()
