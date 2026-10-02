package dev.asl.app;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.content.pm.*;
import android.graphics.Point;
import android.view.WindowManager;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only, authenticated, bounded HTTP interface for the guest tool client. */
public class AndroidBridgeServer implements AutoCloseable {
    private final Context context;
    private final byte[] authorization;
    private volatile boolean running=true;
    private ServerSocket server;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    AndroidBridgeServer(Context context,String token){this.context=context;authorization=("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    void start() throws IOException {
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),8765),8);
        new Thread(()->{while(running)try{Socket socket=server.accept();socket.setSoTimeout(10000);
            try{workers.execute(()->handle(socket));}catch(RejectedExecutionException e){socket.close();}
        }catch(IOException e){if(running)VmService.append("[ASL] 控制桥连接错误。\r\n");}},"asl-android-bridge").start();
    }
    private String line(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int ch;
        while((ch=in.read())!=-1){if(ch=='\n')break;if(ch!='\r')bytes.write(ch);if(bytes.size()>limit)throw new IOException("Header too large");}
        return bytes.toString("UTF-8");
    }
    private void handle(Socket socket){
        try(Socket client=socket){
            InputStream input=client.getInputStream();OutputStream output=client.getOutputStream();
            String[] request=line(input,2048).split(" ");if(request.length<2)return;
            Map<String,String> headers=new HashMap<>();int size=0;
            while(true){String line=line(input,8192);if(line.isEmpty())break;size+=line.length();if(size>8192)throw new IOException("Headers too large");
                int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
            byte[] given=headers.getOrDefault("authorization","").getBytes(StandardCharsets.UTF_8);
            if(!MessageDigest.isEqual(authorization,given)){reply(output,401,new JSONObject().put("error","Unauthorized"));return;}
            if(headers.containsKey("transfer-encoding")){reply(output,400,new JSONObject().put("error","Chunked bodies are unsupported"));return;}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
            if(length<0||length>65536){reply(output,413,new JSONObject().put("error","Body too large"));return;}
            byte[] data=new byte[length];int n=0;while(n<length){int got=input.read(data,n,length-n);if(got<0)throw new EOFException();n+=got;}
            JSONObject body=length==0?new JSONObject():new JSONObject(new String(data,StandardCharsets.UTF_8));
            if(!request[0].equals("POST")){reply(output,405,new JSONObject().put("error","POST required"));return;}
            dispatch(request[1],body,output);
        }catch(Exception ignored){/* Invalid or disconnected clients have no effect on Android. */}
    }
    private void dispatch(String route,JSONObject body,OutputStream output)throws Exception {
        try {
            AndroidControlService service=AndroidControlService.current;
            Point display=new Point();((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(display);
            if(route.equals("/v1/background/status")){reply(output,200,BackgroundDisplay.status());return;}
            if(route.equals("/v1/background/select")){reply(output,200,BackgroundDisplay.select(body.getString("mode")));return;}
            if(route.equals("/v1/background/finish")){String title=body.optString("title","后台任务"),summary=body.optString("summary","点击查看结果");if(title.length()>80||summary.length()>500)throw new IllegalArgumentException("Result title or summary too long");reply(output,200,BackgroundDisplay.finish(title,summary));return;}
            if(route.equals("/v1/app_info")){String pkg=body.getString("package");PackageInfo info=context.getPackageManager().getPackageInfo(pkg,0);reply(output,200,new JSONObject().put("package",pkg).put("version_code",Long.toString(info.getLongVersionCode())).put("version_name",info.versionName));return;}
            if(route.equals("/v1/status")){JSONObject background=BackgroundDisplay.status();if(BackgroundDisplay.selected()&&background.optInt("display_id",-1)>0)display=BackgroundDisplay.size();reply(output,200,new JSONObject().put("version","0.5.0-dev").put("asltree_version","0.5.0").put("accessibility",service!=null)
                .put("vm",VmService.status).put("screen_width",display.x).put("screen_height",display.y)
                .put("terminal_rows",VmService.terminalRows).put("terminal_columns",VmService.terminalColumns)
                .put("background",background).put("battery_unrestricted",context.getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName()))
                .put("memory_mb",context.getSharedPreferences("asl",0).getInt("memory_mb",4096)).put("vcpus",2).put("terminals",new JSONArray(VmService.terminalWindows)));return;}
            if(route.equals("/v1/apps")){
                JSONArray apps=new JSONArray();Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for(ResolveInfo info:context.getPackageManager().queryIntentActivities(launcher,0))apps.put(new JSONObject()
                    .put("package",info.activityInfo.packageName).put("label",info.loadLabel(context.getPackageManager()).toString()));
                reply(output,200,new JSONObject().put("apps",apps));return;
            }
            if(route.equals("/v1/terminal_metrics")){reply(output,200,TerminalSessions.metrics());return;}
            if(service==null){reply(output,409,new JSONObject().put("error","请在平板的无障碍设置中启用 ASL Android 控制"));return;}
            display=BackgroundDisplay.size();
            JSONObject response=new JSONObject();
            switch(route){
                case "/v1/tree":response=service.tree();String pkg=response.optString("foreground_package","");if(!pkg.isEmpty())response.put("app_version",Long.toString(context.getPackageManager().getPackageInfo(pkg,0).getLongVersionCode()));response.put("display_mode",BackgroundDisplay.selected()?"background":"foreground").put("screen_width",display.x).put("screen_height",display.y);break;
                case "/v1/node":response=service.nodeAction(body);break;
                case "/v1/launch":{
                    String name=body.getString("package");if(!name.matches("[A-Za-z0-9_.]+"))throw new IllegalArgumentException("Invalid package");
                    if(BackgroundDisplay.selected()){BackgroundDisplay.request("/launch",body);response=service.settle(name,5000);response.put("ok",response.optBoolean("settled")).put("display_id",BackgroundDisplay.displayId());break;}
                    Intent launch=context.getPackageManager().getLaunchIntentForPackage(name);if(launch==null)throw new IllegalArgumentException("No launchable activity for "+name);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);service.startActivity(launch);response=service.settle(name,5000);response.put("ok",response.optBoolean("settled"));if(!response.optBoolean("settled"))response.put("hint","Target app is not ready or another window is covering it. Read tree/screenshot before acting.");break;
                }
                case "/v1/tap":{
                    float[] p=service.point(body,"x","y",display);boolean ok=service.gesture(p[0],p[1],p[0],p[1],80);
                    response=service.settle(null,1200).put("ok",ok).put("physical_x",p[0]).put("physical_y",p[1]);break;
                }
                case "/v1/swipe":{
                    float[] p=service.point(body,"x1","y1",display),q=service.point(body,"x2","y2",display);int ms=body.optInt("duration_ms",450);
                    if(ms<50||ms>2000)throw new IllegalArgumentException("duration_ms must be 50..2000");boolean ok=service.gesture(p[0],p[1],q[0],q[1],ms);response=service.settle(null,1200).put("ok",ok);break;
                }
                case "/v1/text":{
                    service.checkPackage(body);String text=body.getString("text");if(text.length()>10000)throw new IllegalArgumentException("Text too long");
                    boolean ok=service.text(text);if(!ok)throw new IllegalStateException("Focus an editable input field first; this app may not expose text input");response.put("ok",true);break;
                }
                case "/v1/key":{
                    service.checkPackage(body);String key=body.getString("key");int action;
                    if(BackgroundDisplay.selected()){response=BackgroundDisplay.request("/key",body);break;}
                    if(key.equals("back"))action=AccessibilityService.GLOBAL_ACTION_BACK;
                    else if(key.equals("home"))action=AccessibilityService.GLOBAL_ACTION_HOME;
                    else if(key.equals("recents"))action=AccessibilityService.GLOBAL_ACTION_RECENTS;
                    else throw new IllegalArgumentException("key must be back, home, or recents");
                    boolean ok=service.performGlobalAction(action);response=service.settle(null,1200).put("ok",ok);break;
                }
                case "/v1/screenshot":{
                    int max=body.optInt("max_width",1600);if(max<320||max>3840)throw new IllegalArgumentException("max_width must be 320..3840");
                    AndroidControlService.Screenshot shot=service.capture(max);
                    String extra="X-ASL-Screen-Width: "+shot.width+"\r\nX-ASL-Screen-Height: "+shot.height+"\r\nX-ASL-Image-Width: "+shot.imageWidth+"\r\nX-ASL-Image-Height: "+shot.imageHeight+"\r\nX-ASL-Screenshot-Id: "+service.remember(shot)+"\r\n";
                    raw(output,200,"image/png",shot.png,extra);return;
                }
                default:reply(output,404,new JSONObject().put("error","Unknown route"));return;
            }
            reply(output,200,response);
        }catch(IllegalArgumentException|JSONException e){reply(output,400,new JSONObject().put("error",String.valueOf(e.getMessage())));}
        catch(IllegalStateException e){reply(output,409,new JSONObject().put("error",String.valueOf(e.getMessage())));}
        catch(Exception e){reply(output,500,new JSONObject().put("error",String.valueOf(e.getMessage())));}
    }
    private void checkPoint(float x,float y,Point size){if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=size.x||y>=size.y)throw new IllegalArgumentException("Coordinates "+x+","+y+" outside Android display "+size.x+"x"+size.y);}
    private void reply(OutputStream output,int status,JSONObject data)throws IOException{raw(output,status,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8),"");}
    private void raw(OutputStream output,int status,String type,byte[] data,String extra)throws IOException{
        String header="HTTP/1.1 "+status+" "+(status==200?"OK":"Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n"+extra+"\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));output.write(data);output.flush();
    }
    @Override public void close(){running=false;try{if(server!=null)server.close();}catch(IOException ignored){}workers.shutdownNow();}
}
