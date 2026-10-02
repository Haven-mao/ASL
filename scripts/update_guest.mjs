import { spawnSync } from 'node:child_process';
import { readFileSync,readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
const [keyArg,user,portArg='12222']=process.argv.slice(2);
if(!keyArg||!user||!/^[A-Za-z_][A-Za-z0-9_-]*$/.test(user))throw new Error('Usage: node update_guest.mjs SSH_KEY USER [LOCAL_ADB_PORT]');
const port=Number(portArg);if(!Number.isInteger(port)||port<1||port>65535)throw new Error('Invalid port');
const key=resolve(keyArg),knownHosts=resolve(dirname(key),'asl-known-hosts');
const guestFile=name=>readFileSync(new URL('../guest/'+name,import.meta.url),'utf8');
const asltree=Object.fromEntries(readdirSync(new URL('../guest/asltree/',import.meta.url)).filter(name=>name.endsWith('.py')).map(name=>[name,guestFile('asltree/'+name)]));
const installer=Buffer.from(guestFile('install-terminal-support.py')).toString('base64');
const command=`sudo -n python3 -c "import base64;exec(base64.b64decode('${installer}'))"`;
const result=spawnSync(process.env.ASL_SSH??'ssh',['-T','-p',String(port),'-i',key,'-o','BatchMode=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=5','-o','ServerAliveCountMax=3','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+knownHosts,user+'@127.0.0.1',command],
  {input:JSON.stringify({offline:process.env.ASL_DEFER_RESTART==='1',asltree,agent:guestFile('asl_guest_agent.py'),profile:guestFile('terminal-profile.sh'),tmux:guestFile('tmux.conf'),'asl_pty.py':guestFile('asl_pty.py'),'asl_android.py':guestFile('asl_android.py')}),encoding:'utf8'});
if(result.status!==0)throw new Error(result.stderr||'Guest update failed');
process.stdout.write(result.stdout);
