#!/usr/bin/env python3
"""CLI and dependency-free MCP stdio server for the ASL Android bridge."""
import argparse
import base64
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
try:
    from asltree import mcp as operation_memory
except ImportError:
    operation_memory=None
_memory_engine=None


def config():
    candidates = [pathlib.Path(os.environ.get('ASL_ANDROID_CONFIG', '/etc/asl/android-bridge.json')),
                  pathlib.Path('/sys/firmware/qemu_fw_cfg/by_name/opt/asl/bridge/raw')]
    for file in candidates:
        if file.is_file():
            return json.loads(file.read_bytes().rstrip(b'\0'))
    raise RuntimeError('Android bridge configuration unavailable. Start Linux from the ASL app.')


def call(route, arguments=None):
    cfg = config()
    data = json.dumps(arguments or {}).encode()
    req = urllib.request.Request(cfg['url'] + '/v1/' + route, data=data,
                                 headers={'Authorization': 'Bearer ' + cfg['token'], 'Content-Type': 'application/json'})
    # Do not send local bridge traffic or its token through environment proxies.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=15) as response:
            body = response.read()
            if response.headers.get_content_type() == 'image/png':
                return body, dict(response.headers)
            return json.loads(body)
    except urllib.error.HTTPError as error:
        try:
            message = json.loads(error.read()).get('error', str(error))
        except (ValueError, UnicodeDecodeError):
            message = str(error)
        raise RuntimeError(message) from None
    except (TimeoutError, urllib.error.URLError):
        raise RuntimeError('Android bridge timed out. HyperOS may have frozen ASL in the background. Open ASL, set its battery policy to unrestricted, and retry.') from None


TOOLS = [
    ('android_control_display', 'background/select', 'Choose foreground (tablet main screen) or background (independent virtual screen). Background requires the ASL USB-activated worker. Never fall back to the main screen after background errors.', {'mode': {'type':'string','enum':['foreground','background']}}, ['mode']),
    ('android_background_status', 'background/status', 'Read virtual-screen availability, selected mode, display ID and frame readiness.', {}, []),
    ('android_finish_task', 'background/finish', 'After verifying the requested background task is complete, notify the user with a short title and summary. The user can preview or bring the exact result page onto the main screen; this does not interrupt their foreground app.', {'title':'string','summary':'string'}, ['title','summary']),
    ('android_status', 'status', 'Read Android bridge status, background battery allowance and physical screen size.', {}, []),
    ('android_list_apps', 'apps', 'List launchable Android apps and package names.', {}, []),
    ('android_ui_tree', 'tree', 'Read current visible UI and short-lived node IDs. Prefer node actions. A sparse tree in custom apps such as Douyin requires a screenshot.', {}, []),
    ('android_launch_app', 'launch', 'Launch an installed Android app and wait for its foreground window. Check ok/settled and inspect UI before clicking.', {'package': 'string'}, ['package']),
    ('android_click', 'node', 'Click a visible node ID from the latest UI tree. Rejects stale nodes. Prefer this over coordinates.', {'node_id': 'string', 'expected_package': 'string'}, ['node_id']),
    ('android_set_text', 'node', 'Focus an editable node and replace its text, then verify it. Does not submit. Password fields are excluded.', {'node_id': 'string', 'text': 'string', 'expected_package': 'string'}, ['node_id', 'text']),
    ('android_scroll', 'node', 'Scroll a scrollable node from the latest UI tree.', {'node_id': 'string', 'direction': {'type': 'string', 'enum': ['up','down','left','right']}, 'expected_package': 'string'}, ['node_id', 'direction']),
    ('android_submit_input', 'node', 'Invoke the input field keyboard action, e.g. Search. This submits the field; use only for the user-requested operation.', {'node_id': 'string', 'expected_package': 'string'}, ['node_id']),
    ('android_tap', 'tap', 'Tap x,y from a screenshot: pass its screenshot_id and use IMAGE pixels; ASL scales automatically. Without screenshot_id coordinates must be PHYSICAL pixels. Inspect the result after tapping.', {'x':'number','y':'number','screenshot_id':'string','expected_package':'string'}, ['x','y']),
    ('android_swipe', 'swipe', 'Swipe using IMAGE pixels with screenshot_id (automatic scaling), or PHYSICAL pixels without it. Inspect a fresh screenshot afterward.', {'x1':'number','y1':'number','x2':'number','y2':'number','screenshot_id':'string','duration_ms':'integer','expected_package':'string'}, ['x1','y1','x2','y2']),
    ('android_input_text', 'text', 'Replace the currently focused Android input field. Prefer android_set_text with an editable node ID when available. Does not submit.', {'text':'string','expected_package':'string'}, ['text']),
    ('android_press_key', 'key', 'Press Android back, home, or recents.', {'key':{'type':'string','enum':['back','home','recents']},'expected_package':'string'}, ['key']),
    ('android_screenshot', 'screenshot', 'Capture the screen with a screenshot_id and image dimensions. For taps/swipes pass that ID and IMAGE coordinates; do not manually scale them.', {}, []),
]
NODE_ACTIONS={'android_click':'click','android_set_text':'set_text','android_scroll':'scroll','android_submit_input':'submit'}


def schemas():
    android=[{'name': name, 'description': description,
             **({'annotations': {'readOnlyHint': True, 'destructiveHint': False, 'openWorldHint': False}} if route in ('status','apps','tree','screenshot','background/status') else {}),
             'inputSchema': {'type': 'object', 'properties': {key: {'type': kind} if isinstance(kind,str) else kind for key, kind in props.items()},
                             'required': required, 'additionalProperties': False}}
            for name, route, description, props, required in TOOLS]
    return android+(operation_memory.schemas() if operation_memory else [])


def handle(request):
    method = request.get('method')
    if 'id' not in request:
        return None
    result = {}
    if method == 'initialize':
        result = {'protocolVersion': '2024-11-05', 'capabilities': {'tools': {}},
                  'serverInfo': {'name': 'asl-android', 'version': '0.5.0'},
                  'instructions': 'Control the user\'s Android tablet. First read android_status and respect selected_mode. In background mode all UI tools target the independent virtual display: never switch to foreground as a fallback. Use android_control_display only when the user asks to choose a screen. Read UI before each action. Prefer node click/set_text/scroll. Some apps (e.g. Douyin) expose almost no nodes: use screenshots, pass screenshot_id with IMAGE coordinates for automatic conversion. Always verify the resulting UI. A dispatch success does not prove the intended page opened. On timeout check ASL background battery policy. When a requested background task is complete, verify its result and call android_finish_task with a concise title/summary; the user decides when to bring the result to the main screen. Ask before irreversible or externally visible actions such as sending messages, purchases, or deleting user data unless already authorized. Never bypass Android permissions or secure screens.'}
        if operation_memory:result['instructions']+=' '+operation_memory.INSTRUCTIONS
    elif method == 'ping':
        result = {}
    elif method == 'tools/list':
        result = {'tools': schemas()}
    elif method == 'tools/call':
        params = request.get('params', {})
        if operation_memory and params.get('name','').startswith('asltree_'):
            global _memory_engine
            try:
                if _memory_engine is None:_memory_engine=operation_memory.default_engine(call)
                value=operation_memory.dispatch(_memory_engine,params['name'],params.get('arguments',{}))
                result={'content':[{'type':'text','text':json.dumps(value,ensure_ascii=False)}]}
                if isinstance(value,dict) and value.get('ok') is False:result['isError']=True
            except Exception as error:result={'isError':True,'content':[{'type':'text','text':json.dumps({'error':getattr(error,'code','operation_failed'),'message':str(error)},ensure_ascii=False)}]}
            return {'jsonrpc':'2.0','id':request['id'],'result':result}
        tool = next((tool for tool in TOOLS if tool[0] == params.get('name')), None)
        if not tool:
            return {'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32602, 'message': 'Unknown tool'}}
        try:
            args = params.get('arguments', {})
            if not isinstance(args, dict) or not set(tool[4]).issubset(args) or not set(args).issubset(tool[3]):
                raise ValueError('Arguments must match the tool schema')
            args=dict(args)
            if tool[0] in NODE_ACTIONS:args['action']=NODE_ACTIONS[tool[0]]
            value = call(tool[1], args)
            if isinstance(value, tuple):
                png, headers = value
                dims = {key: headers.get('X-ASL-' + label) for key, label in [('screen_width', 'Screen-Width'), ('screen_height', 'Screen-Height'), ('image_width', 'Image-Width'), ('image_height', 'Image-Height'), ('screenshot_id','Screenshot-Id')]}
                dims['tap_hint']='Pass screenshot_id and coordinates measured in this image to android_tap/android_swipe. ASL converts to display pixels.'
                result = {'content': [{'type': 'text', 'text': json.dumps(dims)},
                                      {'type': 'image', 'mimeType': 'image/png', 'data': base64.b64encode(png).decode()}]}
            else:
                result = {'content': [{'type': 'text', 'text': json.dumps(value, ensure_ascii=False)}]}
        except Exception as error:
            result = {'isError': True, 'content': [{'type': 'text', 'text': str(error)}]}
    else:
        return {'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32601, 'message': 'Method not found'}}
    return {'jsonrpc': '2.0', 'id': request['id'], 'result': result}


def serve():
    for line in sys.stdin:
        try:
            request = json.loads(line)
            response = handle(request)
        except Exception:
            response = {'jsonrpc': '2.0', 'id': None, 'error': {'code': -32700, 'message': 'Invalid JSON-RPC request'}}
        if response is not None:
            print(json.dumps(response, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['status', 'apps', 'tree', 'launch', 'tap', 'swipe', 'text', 'key', 'screenshot', 'mcp'])
    parser.add_argument('args', nargs='*')
    args = parser.parse_args()
    if args.command == 'mcp':
        serve()
        return
    values = args.args
    body = {}
    if args.command == 'launch': body = {'package': values[0]}
    if args.command == 'tap': body = dict(zip(['x', 'y'], map(float, values)))
    if args.command == 'swipe': body = dict(zip(['x1', 'y1', 'x2', 'y2'], map(float, values)))
    if args.command == 'text': body = {'text': ' '.join(values)}
    if args.command == 'key': body = {'key': values[0]}
    value = call(args.command, body)
    if isinstance(value, tuple):
        destination = pathlib.Path(values[0] if values else 'android-screen.png')
        destination.write_bytes(value[0])
        print(destination.resolve())
    else:
        print(json.dumps(value, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
