import { execFileSync } from 'node:child_process';
import { statSync } from 'node:fs';
import { resolve } from 'node:path';

const [adbArg,archiveArg]=process.argv.slice(2);
if(!adbArg||!archiveArg)throw new Error('Usage: node provision_device.mjs /path/to/adb /path/to/asl-device-image.tar');
const adb=resolve(adbArg),archive=resolve(archiveArg);
if(execFileSync(adb,['-d','get-state'],{encoding:'utf8'}).trim()!=='device')throw new Error('Connect one authorized USB device');
let diskExists=false;
try {execFileSync(adb,['-d','shell','run-as','dev.asl.app','test','-e','files/vm/rootfs.qcow2'],{stdio:'pipe'});diskExists=true;}catch(error){if(error.status!==1)throw error;}
if(diskExists)throw new Error('Existing Linux disk detected. Refusing to overwrite user data.');
const nonce=Date.now(),remote='/data/local/tmp/asl-provision-'+nonce+'.tar',staging='files/import-'+nonce;
const run=(...args)=>execFileSync(adb,['-d','shell','run-as','dev.asl.app',...args],{encoding:'utf8',timeout:120000});
run('mkdir','-p','files/vm',staging);
execFileSync(adb,['-d','push',archive,remote],{stdio:'inherit',timeout:300000});
// Toybox tar on this device requires a seekable file for the image archive.
run('tar','-x','-o','-m','-f',remote,'-C',staging);
const manifest=JSON.parse(run('cat',staging+'/manifest.json'));
for(const [name,expected] of Object.entries(manifest.sha256)){
    if(!['kernel','initrd','config.json','rootfs.qcow2'].includes(name))throw new Error('Unexpected image filename');
    const result=run('sha256sum',staging+'/'+name).split(/\s+/)[0];
    if(result!==expected)throw new Error('Device checksum mismatch: '+name);
}
for(const name of ['kernel','initrd','config.json','rootfs.qcow2','manifest.json','READY']){
    run('chmod','600',staging+'/'+name);run('mv',staging+'/'+name,'files/vm/'+name);
}
run('rmdir',staging);
execFileSync(adb,['-d','shell','rm',remote]);
console.log(JSON.stringify({installed:true,archiveBytes:statSync(archive).size,verifiedFiles:Object.keys(manifest.sha256)}));
