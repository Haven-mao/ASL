package dev.asl.app;

import android.app.*;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.*;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.security.MessageDigest;

public class VmService extends Service {
    public static final String START="dev.asl.START", SHUTDOWN="dev.asl.SHUTDOWN", BRIDGE="dev.asl.BRIDGE", VERSION="dev.asl.VERSION", IMPORT="dev.asl.IMPORT";
    public static volatile String status="Linux 未启动";
    public static volatile int terminalRows=40,terminalColumns=120;
    private static volatile Process vm;
    private static final StringBuilder log = new StringBuilder();
    private static long logBase;
    private static long lastInputWarning;
    private static final ExecutorService consoleWriter=Executors.newSingleThreadExecutor();
    private volatile boolean autoTerminalCreated;
    private static final Set<Runnable> outputListeners=new CopyOnWriteArraySet<>();
    public static volatile String terminalWindows="[]";
    private final ScheduledExecutorService guestMonitor=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AndroidBridgeServer bridge;
    private PowerManager.WakeLock wakeLock;
    private File vmDir;
    private static String bridgeToken;
    public static boolean imageReady(Context context){
        File dir=new File(context.getFilesDir(),"vm");
        for(String name:new String[]{"READY","kernel","initrd","rootfs.qcow2","config.json"})if(!new File(dir,name).isFile())return false;
        return true;
    }

    static synchronized void append(String text) {
        log.append(text);
        if (log.length()>1000000) { int remove=log.length()-800000; log.delete(0,remove); logBase+=remove; }
        notifyOutput();
    }
    public static void addOutputListener(Runnable listener){outputListeners.add(listener);}
    public static void removeOutputListener(Runnable listener){outputListeners.remove(listener);}
    private static void notifyOutput(){for(Runnable listener:outputListeners)listener.run();}
    static void signalOutput(){notifyOutput();}
    public static synchronized String readLog(long cursor) {
        try { int start=(int)Math.max(0,Math.min(log.length(),cursor-logBase));
            return new JSONObject().put("text",log.substring(start)).put("next",logBase+log.length()).toString();
        } catch(Exception e) { return "{}"; }
    }
    public static void input(String text) {
        input(TerminalSessions.active,text);
    }
    public static void input(String id,String text) {
        if(!id.equals("console")){TerminalSessions.input(id,text);return;}
        Process p=vm;
        if (p==null || !p.isAlive()) {long now=SystemClock.elapsedRealtime();if(now-lastInputWarning>5000){lastInputWarning=now;append("\r\n[ASL] Linux 尚未启动，启动后即可输入。\r\n");}return;}
        consoleWriter.execute(()->{try { synchronized(p) { p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); p.getOutputStream().flush(); } }
        catch(IOException e) { append("\r\n[ASL] 输入失败: "+e.getMessage()+"\r\n"); }});
    }
    @Override public void onCreate() {
        super.onCreate(); vmDir=new File(getFilesDir(),"vm"); vmDir.mkdirs();
        new File(vmDir,"console.log").delete(); // Earlier development builds persisted console output.
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("asl", "ASL Linux", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1,new Notification.Builder(this,"asl").setSmallIcon(getResources().getIdentifier("ic_terminal_status","drawable",getPackageName())).setContentTitle("ASL 终端")
            .setContentText("Linux 虚拟机与 Android 控制桥").setContentIntent(open).setOngoing(true).build());
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"asl:vm");
        try {
            File secret=new File(getFilesDir(),"bridge-token");
            if(!secret.exists()) { byte[] data=new byte[32]; new SecureRandom().nextBytes(data);
                StringBuilder hex=new StringBuilder(); for(byte b:data)hex.append(String.format("%02x",b));
                Files.write(secret.toPath(),hex.toString().getBytes(StandardCharsets.UTF_8)); }
            String token=new String(Files.readAllBytes(secret.toPath()),StandardCharsets.UTF_8).trim();
            bridgeToken=token;
            TerminalSessions.initialize(token);
            BackgroundDisplay.initialize(this,token);
            JSONObject config=new JSONObject().put("url","http://10.0.2.2:8765").put("token",token);
            Files.write(new File(vmDir,"bridge.json").toPath(),config.toString().getBytes(StandardCharsets.UTF_8));
            bridge=new AndroidBridgeServer(this,token); bridge.start();
            append("\r\nASL 0.3 · Linux 与 Android 的连接已准备就绪。\r\n");
        }catch(Exception e){ append("[ASL] 控制桥启动失败: "+e.getMessage()+"\r\n"); }
        guestMonitor.scheduleWithFixedDelay(()->{if(vm!=null&&vm.isAlive()&&!autoTerminalCreated)try{guestRequest("/health",new JSONObject());autoTerminalCreated=true;status="Linux 运行中";TerminalSessions.create();}catch(Exception ignored){}},2,2,TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null) { String action=intent.getAction();
            if(START.equals(action))executor.submit(this::startVm);
            if(SHUTDOWN.equals(action))executor.submit(this::shutdownVm);
            if(VERSION.equals(action))executor.submit(this::version);
            if(IMPORT.equals(action)&&intent.getStringExtra("uri")!=null)executor.submit(()->importImage(Uri.parse(intent.getStringExtra("uri"))));
            if("dev.asl.NEW_TERMINAL".equals(action)){if(vm!=null&&vm.isAlive())TerminalSessions.create();else append("\r\n[ASL] 请先启动 Linux。\r\n");}
            if("dev.asl.SELECT_TERMINAL".equals(action))TerminalSessions.select(intent.getStringExtra("id"));
        }
        return START_NOT_STICKY;
    }
    private ArrayList<String> engine() {
        String lib=getApplicationInfo().nativeLibraryDir;
        return new ArrayList<>(Arrays.asList(lib+"/libqemu-system-aarch64.so"));
    }
    private Process spawn(List<String> args) throws IOException {
        ProcessBuilder pb=new ProcessBuilder(args); pb.directory(vmDir); pb.redirectErrorStream(true);
        pb.environment().put("TMPDIR",getCacheDir().getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH",getApplicationInfo().nativeLibraryDir);
        pb.environment().put("QEMU_MODULE_DIR",getApplicationInfo().nativeLibraryDir);
        pb.environment().put("HOME",vmDir.getAbsolutePath());
        return pb.start();
    }
    private void version() {
        try { ArrayList<String> args=engine(); args.add("--version"); Process p=spawn(args); consume(p); p.waitFor(); }
        catch(Exception e){append("[ASL] 引擎检测失败: "+e.getMessage()+"\r\n");}
    }
    private synchronized void startVm() {
        if(vm!=null && vm.isAlive()) { append("[ASL] Linux 已在运行。\r\n");return; }
        try {
            if(!imageReady(this)){status="系统未就绪";append("[ASL] 请先导入完整的 ASL 系统镜像。\r\n");return;}
            for(String file:new String[]{"kernel","initrd","rootfs.qcow2","config.json"})
                if(!new File(vmDir,file).isFile())throw new IOException("缺少系统文件 "+file+"；请先导入 ASL 系统镜像。");
            JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(vmDir,"config.json").toPath()),StandardCharsets.UTF_8));
            ArrayList<String> args=engine();
            int memory=Math.max(1024,Math.min(4096,getSharedPreferences("asl",0).getInt("memory_mb",4096)));
            Collections.addAll(args,"-name","ASL","-machine","virt","-cpu","cortex-a76","-accel","tcg,thread=multi","-smp","2","-m",Integer.toString(memory),
                "-nodefaults","-nographic","-monitor","none","-serial","stdio",
                "-kernel",new File(vmDir,"kernel").toString(),"-initrd",new File(vmDir,"initrd").toString(),
                "-append","root="+config.getString("root")+" rw console=ttyAMA0,115200 loglevel=3 systemd.show_status=false net.ifnames=0",
                "-drive","if=none,id=root,file="+new File(vmDir,"rootfs.qcow2")+",format=qcow2,cache=writeback",
                "-device","virtio-blk-device,drive=root",
                "-netdev","user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22,hostfwd=tcp:127.0.0.1:2223-:2020",
                "-device","virtio-net-device,netdev=net0",
                "-device","virtio-rng-device",
                "-fw_cfg","name=opt/asl/bridge,file="+new File(vmDir,"bridge.json"),
                "-qmp","unix:"+new File(vmDir,"qmp.sock")+",server=on,wait=off");
            File socket=new File(vmDir,"qmp.sock"); if(socket.exists()&&!socket.delete())throw new IOException("无法移除失效的 QMP socket");
            append("\r\n[ASL] 正在启动 Debian ARM64 · "+memory+" MB · 2 vCPU\r\n");
            status="Linux 启动中";autoTerminalCreated=false;Process process=spawn(args);vm=process;wakeLock.acquire();
            new Thread(()->{
                try { consume(process);int code=process.waitFor();append("\r\n[ASL] Linux 已退出，代码 "+code+"。\r\n"); }
                catch(Exception e){append("[ASL] "+e.getMessage()+"\r\n");}
                finally {vm=null;status="Linux 已停止";TerminalSessions.stopped();notifyOutput();if(wakeLock.isHeld())wakeLock.release();}
            },"asl-console").start();
        }catch(Exception e){status="启动失败";append("[ASL] "+e.getMessage()+"\r\n");}
    }
    private void consume(Process p) throws IOException {
        InputStreamReader reader=new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8);char[] buf=new char[4096];int n;
        while((n=reader.read(buf))!=-1){String text=new String(buf,0,n);append(text);if(text.contains("ASL Linux ready")||text.contains("@asl"))status="Linux 运行中";}
    }
    private void importImage(Uri uri){
        File staging=new File(getFilesDir(),"import-"+UUID.randomUUID());staging.mkdirs();
        try{
            if(vm!=null&&vm.isAlive())throw new IOException("请先关闭 Linux");
            if(new File(vmDir,"rootfs.qcow2").exists())throw new IOException("已有 Linux 磁盘，不能覆盖现有数据");
            Set<String> allowed=new HashSet<>(Arrays.asList("kernel","initrd","rootfs.qcow2","config.json","manifest.json"));
            Set<String> found=new HashSet<>();long total=0;
            status="正在导入系统";append("[ASL] 正在导入并校验系统镜像。\r\n");
            try(ZipInputStream zip=new ZipInputStream(getContentResolver().openInputStream(uri))){ZipEntry entry;byte[] buffer=new byte[1024*1024];
                while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(entry.isDirectory()||!allowed.contains(name)||!found.add(name))throw new IOException("镜像包包含无效文件");
                    try(FileOutputStream file=new FileOutputStream(new File(staging,name))){int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>7L*1024*1024*1024)throw new IOException("镜像包超过容量上限");file.write(buffer,0,n);}}
                    zip.closeEntry();
                }
            }
            if(!found.equals(allowed))throw new IOException("镜像包不完整");
            JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(staging,"manifest.json").toPath()),StandardCharsets.UTF_8));
            for(String name:new String[]{"kernel","initrd","rootfs.qcow2","config.json"}){
                MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(new File(staging,name))){byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
                StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b));
                if(!hex.toString().equals(manifest.getJSONObject("sha256").getString(name)))throw new IOException("镜像校验失败: "+name);
            }
            for(String name:new String[]{"kernel","initrd","config.json","rootfs.qcow2"})Files.move(new File(staging,name).toPath(),new File(vmDir,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(new File(vmDir,"READY").toPath(),"ASL 0.1\n".getBytes(StandardCharsets.UTF_8));
            status="Linux 未启动";append("[ASL] 系统镜像已就绪，可以启动 Linux。\r\n");
        }catch(Exception e){status="导入失败";append("[ASL] "+e.getMessage()+"\r\n");}
        finally{File[] files=staging.listFiles();if(files!=null)for(File file:files)file.delete();staging.delete();}
    }
    private void shutdownVm() {
        if(vm==null||!vm.isAlive())return;
        try(java.net.Socket socket=new java.net.Socket()){
            socket.connect(new java.net.InetSocketAddress("127.0.0.1",2223),3000);socket.setSoTimeout(5000);
            String request="POST /shutdown HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+bridgeToken+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            String result=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            if(result!=null&&result.contains("202")){status="Linux 正在关机";append("\r\n[ASL] 已请求 Linux 保存数据并关机。\r\n");return;}
        }catch(IOException ignored){}
        try(LocalSocket socket=new LocalSocket()) {
            socket.connect(new LocalSocketAddress(new File(vmDir,"qmp.sock").toString(),LocalSocketAddress.Namespace.FILESYSTEM));socket.setSoTimeout(4000);
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));reader.readLine();
            OutputStream output=socket.getOutputStream();
            output.write("{\"execute\":\"qmp_capabilities\"}\r\n".getBytes(StandardCharsets.UTF_8));output.flush();reader.readLine();
            output.write("{\"execute\":\"system_powerdown\"}\r\n".getBytes(StandardCharsets.UTF_8));output.flush();
            status="Linux 正在关机";append("\r\n[ASL] 已请求 Linux 正常关机。\r\n");
        }catch(Exception e){append("[ASL] 请在终端执行 sudo poweroff："+e.getMessage()+"\r\n");}
    }
    static JSONObject guestRequest(String route,JSONObject body)throws Exception{
        byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
        try(java.net.Socket socket=new java.net.Socket()){
            socket.setTcpNoDelay(true);socket.connect(new java.net.InetSocketAddress("127.0.0.1",2223),1500);socket.setSoTimeout(6000);
            String request="POST "+route+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+bridgeToken+"\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n";
            OutputStream output=socket.getOutputStream();output.write(request.getBytes(StandardCharsets.US_ASCII));output.write(data);output.flush();
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String first=reader.readLine();String line;
            while((line=reader.readLine())!=null&&!line.isEmpty()){}
            StringBuilder response=new StringBuilder();char[] buffer=new char[2048];int n;while((n=reader.read(buffer))!=-1){response.append(buffer,0,n);if(response.length()>65536)throw new IOException("Guest response too large");}
            JSONObject json=new JSONObject(response.toString());if(first==null||!first.contains(" 200 "))throw new IOException(json.optString("error","Linux 终端尚未就绪"));return json;
        }
    }
    @Override public void onDestroy(){ if(bridge!=null)bridge.close();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();executor.shutdown();guestMonitor.shutdownNow();super.onDestroy(); }
    @Override public IBinder onBind(Intent intent){return null;}
}
