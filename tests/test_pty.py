#!/usr/bin/env python3
"""Exercise two real guest PTYs without touching existing user sessions."""
import http.client
import json
import pathlib
import re
import socket
import struct
import time

TOKEN=json.loads(pathlib.Path('/etc/asl/android-bridge.json').read_text())['token']


def request(route,body=None):
    conn=http.client.HTTPConnection('127.0.0.1',2020,timeout=10)
    conn.request('POST',route,json.dumps(body or {}),{'Authorization':'Bearer '+TOKEN})
    response=conn.getresponse();data=json.loads(response.read());conn.close()
    assert response.status==200,(response.status,data)
    return data


class Terminal:
    def __init__(self):
        self.socket=socket.create_connection(('127.0.0.1',2020),10)
        self.socket.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
        body=b'{"columns":120,"rows":40}'
        self.socket.sendall(('POST /pty HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer '+TOKEN+'\r\nContent-Length: '+str(len(body))+'\r\n\r\n').encode()+body)
        headers=b''
        while b'\r\n\r\n' not in headers:headers+=self.socket.recv(1)
        assert b' 200 ' in headers
        self.id=re.search(br'X-ASL-Session: (\w+)',headers).group(1).decode()
        self.read_until(b'$ ')
    def frame(self,kind,payload):self.socket.sendall(struct.pack('>BI',kind,len(payload))+payload)
    def command(self,text):self.frame(1,text.encode()+b'\r')
    def read_until(self,marker,timeout=10):
        received=b'';deadline=time.monotonic()+timeout
        while marker not in received:
            self.socket.settimeout(max(.05,deadline-time.monotonic()))
            part=self.socket.recv(65536)
            if not part:raise AssertionError('Unexpected EOF')
            received+=part
        return received
    def done(self):
        try:request('/pty/close',{'id':self.id,'force':True})
        finally:self.socket.close()


def main():
    one=Terminal();two=Terminal()
    try:
        info={s['id']:s for s in request('/pty/sessions')['sessions']}
        assert info[one.id]['pid']!=info[two.id]['pid']
        one.command("printf 'ACCOUNT:%s HOME:%s\\n' \"$(id -u)\" \"$HOME\"")
        assert b'ACCOUNT:1000 HOME:' in one.read_until(b'ACCOUNT:1000 HOME:')
        one.frame(2,struct.pack('>II',101,31));one.command("stty size; printf 'RESIZE_%s\\n' DONE")
        assert b'31 101' in one.read_until(b'RESIZE_DONE')
        # Deliberately split a multibyte character across input frames.
        raw="printf '\\nUTF_%s\\n' '中文✓'\r".encode()
        for byte in raw:one.frame(1,bytes([byte]))
        assert 'UTF_中文✓'.encode() in one.read_until('UTF_中文✓'.encode())
        one.command('sleep 30')
        deadline=time.monotonic()+5
        while not {s['id']:s for s in request('/pty/sessions')['sessions']}[one.id]['busy']:
            assert time.monotonic()<deadline;time.sleep(.05)
        refused=request('/pty/close',{'id':one.id})
        assert refused.get('needs_confirmation') is True
        two.command("printf 'SECOND_%s\\n' ALIVE")
        assert b'SECOND_ALIVE' in two.read_until(b'SECOND_ALIVE')
        request('/pty/close',{'id':one.id,'force':True})
        two.command("printf 'AFTER_CLOSE_%s\\n' ALIVE")
        assert b'AFTER_CLOSE_ALIVE' in two.read_until(b'AFTER_CLOSE_ALIVE')
        two.command("python3 -u -c 'import time;[(print(\"STREAM\",i),time.sleep(.04)) for i in range(12)]'")
        started=time.monotonic();arrivals={};data=b''
        while len(arrivals)<12:
            two.socket.settimeout(8)
            try:data+=two.socket.recv(16384)
            except TimeoutError:raise AssertionError(repr(data)) from None
            for match in re.finditer(br'(?<=[\r\n])STREAM (\d+)(?=\r?\n)',data):arrivals.setdefault(int(match.group(1)),time.monotonic()-started)
        values=[arrivals[i] for i in range(12)]
        assert values[-1]-values[0]>.25,'Output was buffered until the command finished'
        print(json.dumps({'passed':True,'independent_sessions':True,'resize':True,'utf8':True,'busy_close_confirmation':True,'other_terminal_survives_close':True,'stream_arrivals_ms':[round(v*1000) for v in values]}))
    finally:
        one.done();two.done()


if __name__=='__main__':main()
