"""Authenticated, independent PTY connections for ASL's terminal tabs."""
import errno
import fcntl
import json
import os
import pathlib
import pty
import pwd
import selectors
import signal
import socket
import struct
import subprocess
import termios
import threading
import uuid

SESSIONS={}
LOCK=threading.RLock()


class PtySession:
    def __init__(self, connection, columns, rows):
        self.id=uuid.uuid4().hex
        self.connection=connection
        self.stopping=False
        self.cleaned=False
        if not 20<=columns<=500 or not 5<=rows<=300:raise ValueError('Invalid terminal dimensions')
        self.master,self.slave=pty.openpty()
        self.resize(columns,rows)
        user=pwd.getpwuid(1000)
        env=os.environ.copy()
        env.update(HOME=user.pw_dir,USER=user.pw_name,LOGNAME=user.pw_name,SHELL='/bin/bash',TERM='xterm-256color',COLORTERM='truecolor',LANG='C.UTF-8')
        env.pop('TMUX',None)
        login='import fcntl,os,termios;fcntl.ioctl(0,termios.TIOCSCTTY,0);os.execv("/bin/bash",["-bash"])'
        try:
            self.process=subprocess.Popen(['/usr/bin/python3','-c',login],stdin=self.slave,stdout=self.slave,stderr=self.slave,
                cwd=user.pw_dir,env=env,user=user.pw_uid,group=user.pw_gid,extra_groups=os.getgrouplist(user.pw_name,user.pw_gid),start_new_session=True,close_fds=True)
        except Exception:
            os.close(self.master);os.close(self.slave);raise
        os.close(self.slave)

    def resize(self,columns,rows):
        if not 20<=columns<=500 or not 5<=rows<=300:raise ValueError('Invalid terminal dimensions')
        fcntl.ioctl(self.master,termios.TIOCSWINSZ,struct.pack('HHHH',rows,columns,0,0))

    def info(self):
        command='bash';busy=False
        try:
            foreground=os.tcgetpgrp(self.master)
            busy=foreground>1 and foreground!=self.process.pid
            command=pathlib.Path('/proc')/str(foreground)/'comm'
            command=command.read_text().strip()[:80]
        except (OSError,ProcessLookupError):command='运行中的任务' if busy else 'bash'
        return {'id':self.id,'pid':self.process.pid,'busy':busy,'command':command,'closed':self.process.poll() is not None}

    def stop(self):
        self.stopping=True
        try:self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:pass

    def redraw(self):
        try:
            foreground=os.tcgetpgrp(self.master)
            if foreground>1:os.killpg(foreground,signal.SIGWINCH)
        except (OSError,ProcessLookupError):pass

    def pump(self):
        selector=selectors.DefaultSelector()
        self.connection.setblocking(False);os.set_blocking(self.master,False)
        selector.register(self.connection,selectors.EVENT_READ,'socket')
        selector.register(self.master,selectors.EVENT_READ,'pty')
        incoming=bytearray();to_pty=bytearray();to_socket=bytearray();pty_eof=False
        try:
            while not self.stopping:
                socket_flags=selectors.EVENT_READ|(selectors.EVENT_WRITE if to_socket else 0)
                selector.modify(self.connection,socket_flags,'socket')
                if not pty_eof:selector.modify(self.master,selectors.EVENT_READ|(selectors.EVENT_WRITE if to_pty else 0),'pty')
                if pty_eof and not to_socket:break
                for key,events in selector.select(0.5):
                    if key.data=='socket':
                        if events&selectors.EVENT_READ:
                            data=self.connection.recv(65536)
                            if not data:return
                            incoming.extend(data)
                            while len(incoming)>=5:
                                kind=incoming[0];length=struct.unpack_from('>I',incoming,1)[0]
                                if length>65536:raise ValueError('Terminal frame too large')
                                if len(incoming)<5+length:break
                                body=bytes(incoming[5:5+length]);del incoming[:5+length]
                                if kind==1:to_pty.extend(body)
                                elif kind==2 and length==8:self.resize(*struct.unpack('>II',body))
                                elif kind==3:return
                                elif kind==4:self.redraw()
                                else:raise ValueError('Unknown terminal frame')
                            if len(to_pty)>2*1024*1024:raise ValueError('Terminal input buffer full')
                        if events&selectors.EVENT_WRITE and to_socket:
                            try:count=self.connection.send(to_socket);del to_socket[:count]
                            except BlockingIOError:pass
                    else:
                        if events&selectors.EVENT_READ:
                            try:data=os.read(self.master,16384)
                            except OSError as error:
                                if error.errno==errno.EIO:data=b''
                                elif error.errno==errno.EAGAIN:continue
                                else:raise
                            if not data:
                                pty_eof=True;selector.unregister(self.master)
                            else:to_socket.extend(data)
                            if len(to_socket)>4*1024*1024:raise ValueError('Terminal output buffer full')
                        if events&selectors.EVENT_WRITE and to_pty and not pty_eof:
                            try:count=os.write(self.master,to_pty);del to_pty[:count]
                            except BlockingIOError:pass
        finally:
            selector.close()
            self.cleanup()

    def cleanup(self):
        if self.cleaned:return
        self.cleaned=True
        try:
            foreground=os.tcgetpgrp(self.master)
            if foreground>1:os.killpg(foreground,signal.SIGHUP)
        except (OSError,ProcessLookupError):pass
        os.close(self.master)
        if self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait()


def serve_terminal(handler,body):
    with LOCK:
        if len(SESSIONS)>=16:raise ValueError('最多同时打开 16 个终端')
        session=PtySession(handler.connection,int(body.get('columns',128)),int(body.get('rows',35)))
        SESSIONS[session.id]=session
    try:
        handler.send_response(200)
        handler.send_header('Content-Type','application/octet-stream')
        handler.send_header('X-ASL-Session',session.id)
        handler.send_header('Connection','close')
        handler.end_headers();handler.wfile.flush()
        session.pump()
    except (ConnectionError,OSError,ValueError):
        pass
    finally:
        session.cleanup()
        with LOCK:SESSIONS.pop(session.id,None)
        handler.close_connection=True


def session_operation(route,body):
    with LOCK:
        if route=='/pty/sessions':return {'sessions':[session.info() for session in SESSIONS.values()]}
        session=SESSIONS.get(body.get('id'))
        if session is None:return {'ok':True,'closed':True}
        info=session.info()
        if info['busy'] and not body.get('force',False):return {'ok':False,'needs_confirmation':True,'command':info['command']}
        session.stop()
        return {'ok':True}
