import {execFileSync,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const adb=process.env.ASL_ADB??'adb', expectedVersion='0.5.0';
const token=execFileSync(adb,['-d','shell','run-as','dev.asl.app','cat','files/bridge-token'],{encoding:'utf8',timeout:10000}).trim();
if(!/^[0-9a-f]{64}$/.test(token))throw new Error('ASL must be installed and opened first');
const port=execFileSync(adb,['-d','forward','tcp:0','tcp:8766'],{encoding:'utf8',timeout:10000}).trim();
if(!/^\d+$/.test(port))throw new Error('Could not forward the background bridge');
async function request(route,body={}){const response=await fetch('http://127.0.0.1:'+port+'/'+route,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(4000)});if(!response.ok)throw new Error('Worker rejected '+route);return response.json();}
async function ready(){try{const status=await request('status');return status.available?status:null;}catch{return null;}}
try {
  let current=await ready();
  if(current&&process.argv.includes('--replace')){
    await request('stop');
    const listing=execFileSync(adb,['-d','shell','ps','-A','-o','PID,ARGS'],{encoding:'utf8',timeout:10000});
    const matches=[...listing.matchAll(/^\s*(\d+)\s+app_process \/ dev\.asl\.shell\.DisplayWorker \/data\/local\/tmp\/asl-display-token\s*$/gm)];
    if(matches.length!==1)throw new Error('Cannot uniquely identify the ASL background worker; no process was killed');
    const pid=matches[0][1];
    const command=execFileSync(adb,['-d','shell','cat','/proc/'+pid+'/cmdline'],{encoding:'utf8',timeout:5000});
    if(command.replaceAll('\0',' ').trim()!=='app_process / dev.asl.shell.DisplayWorker /data/local/tmp/asl-display-token')throw new Error('Worker identity changed; no process was killed');
    execFileSync(adb,['-d','shell','kill','-TERM',pid],{stdio:'pipe',timeout:5000});
    for(let attempt=0;attempt<20&&await ready();attempt++)await new Promise(resolve=>setTimeout(resolve,100));
    current=await ready();if(current)throw new Error('Previous worker did not stop');
  }
  if(current){
    if(current.version!==expectedVersion)throw new Error('An older background worker is running. Save its work, then run this command with --replace.');
    console.log('ASL '+expectedVersion+' background worker is already ready.');
  } else {
    execFileSync(adb,['-d','push',fileURLToPath(new URL('../../ASL-display-worker.jar',import.meta.url)),'/data/local/tmp/asl-display-worker.jar'],{stdio:'pipe',timeout:20000});
    const script='umask 077; read -r ASL_DISPLAY_TOKEN; printf "%s" "$ASL_DISPLAY_TOKEN" > /data/local/tmp/asl-display-token; CLASSPATH=/data/local/tmp/asl-display-worker.jar nohup app_process / dev.asl.shell.DisplayWorker /data/local/tmp/asl-display-token </dev/null >/data/local/tmp/asl-display-worker.log 2>&1 &';
    const result=spawnSync(adb,['-d','shell',script],{input:token+'\n',encoding:'utf8',timeout:10000});
    if(result.status!==0)throw new Error('Could not activate the background display worker');
    let started=null;for(let attempt=0;attempt<10;attempt++){started=await ready();if(started)break;await new Promise(resolve=>setTimeout(resolve,250));}
    if(started?.version!==expectedVersion)throw new Error('Expected worker did not become ready. Inspect /data/local/tmp/asl-display-worker.log.');
    console.log('ASL background worker ready. In ASL, choose Android 控制屏幕 → 后台虚拟屏幕.');
  }
} finally {execFileSync(adb,['-d','forward','--remove','tcp:'+port],{stdio:'pipe',timeout:5000});}
