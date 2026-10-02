package dev.asl.shell;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** USB-debugging activated display owner. It can only inject into its own display. */
public final class DisplayWorker {
    private final Context context;
    private final byte[] authorization;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread imageThread=new HandlerThread("asl-background-images");
    private final ExecutorService actions=Executors.newSingleThreadExecutor();
    private VirtualDisplay display;
    private ImageReader reader;
    private PowerManager.WakeLock screenWake;
    private volatile byte[] frame;
    private volatile long frameTime,lastEncode;
    private volatile int displayId=-1;
    private volatile String packageName="";
    private final int width,height,density;

    private DisplayWorker(Context context,String token){this.context=context;android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;density=metrics.densityDpi;authorization=("Bearer "+token).getBytes(StandardCharsets.UTF_8);imageThread.start();main.post(new Runnable(){public void run(){try{if(displayId>=0&&!packageName.isEmpty()&&!context.getSystemService(KeyguardManager.class).isKeyguardLocked())keepAwake();else releaseWake();}catch(Exception ignored){}main.postDelayed(this,1000);}});}
    public static void main(String[] args)throws Exception {
        if(android.os.Process.myUid()!=2000)throw new SecurityException("Start through authorized Android USB debugging");
        Looper.prepareMainLooper();Class<?> activity=Class.forName("android.app.ActivityThread");Object thread=activity.getMethod("systemMain").invoke(null);
        Context system=(Context)activity.getMethod("getSystemContext").invoke(thread);
        File secret=new File(args[0]);String token=new String(Files.readAllBytes(secret.toPath()),StandardCharsets.UTF_8).trim();secret.delete();
        if(!token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid ASL token");
        DisplayWorker worker=new DisplayWorker(system.createPackageContext("com.android.shell",0),token);worker.serve();Looper.loop();
    }
    private void unlocked(){if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("Unlock the tablet to use its background screen");}
    private <T>T onMain(Callable<T> action)throws Exception {FutureTask<T> task=new FutureTask<>(action);main.post(task);return task.get(8,TimeUnit.SECONDS);}
    private synchronized JSONObject status()throws JSONException{return new JSONObject().put("available",true).put("protocol",1).put("version","0.5.0").put("pid",android.os.Process.myPid()).put("display_id",displayId).put("width",width).put("height",height).put("package",packageName).put("frame_ready",frame!=null).put("frame_age_ms",frame==null?-1:SystemClock.elapsedRealtime()-frameTime);}
    private void keepAwake()throws Exception{if(displayId<0)return;if(screenWake==null){PowerManager power=context.getSystemService(PowerManager.class);screenWake=(PowerManager.WakeLock)PowerManager.class.getMethod("newWakeLock",int.class,String.class,int.class).invoke(power,PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"asl:background-display",displayId);screenWake.setReferenceCounted(false);}if(!screenWake.isHeld())screenWake.acquire();}
    private void releaseWake(){if(screenWake!=null&&screenWake.isHeld())screenWake.release();}
    private synchronized JSONObject create()throws Exception {
        unlocked();if(displayId>=0){onMain(()->{keepAwake();return null;});return status();}
        onMain(()->{Constructor<DisplayManager> ctor=DisplayManager.class.getDeclaredConstructor(Context.class);ctor.setAccessible(true);DisplayManager manager=ctor.newInstance(context);
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
            reader.setOnImageAvailableListener(this::image,new Handler(imageThread.getLooper()));
            // OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED preserves the physical keyboard's focus.
            // Never use ALWAYS_UNLOCKED or capture secure surfaces.
            int flags=1|2|8|(1<<6)|(1<<8)|(1<<9)|(1<<10)|(1<<11)|(1<<13)|(1<<14)|(1<<16);
            display=manager.createVirtualDisplay("ASL Background",width,height,density,reader.getSurface(),flags);
            displayId=display.getDisplay().getDisplayId();keepAwake();return null;});return status();
    }
    private void image(ImageReader source){
        try(Image image=source.acquireLatestImage()){
            if(image==null||SystemClock.elapsedRealtime()-lastEncode<160)return;lastEncode=SystemClock.elapsedRealtime();
            Image.Plane plane=image.getPlanes()[0];int paddedWidth=plane.getRowStride()/plane.getPixelStride();
            Bitmap full=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888);full.copyPixelsFromBuffer(plane.getBuffer());Bitmap cropped=Bitmap.createBitmap(full,0,0,width,height);
            Bitmap preview=width>1600?Bitmap.createScaledBitmap(cropped,1600,(int)((long)height*1600/width),true):cropped;
            ByteArrayOutputStream out=new ByteArrayOutputStream();preview.compress(Bitmap.CompressFormat.JPEG,82,out);frame=out.toByteArray();frameTime=SystemClock.elapsedRealtime();if(preview!=cropped)preview.recycle();if(cropped!=full)cropped.recycle();full.recycle();
        }catch(Exception ignored){}
    }
    private String command(String... args)throws Exception {java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Android command timeout");}ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] data=new byte[4096];int n;while((n=p.getInputStream().read(data))!=-1){bytes.write(data,0,n);if(bytes.size()>500000)throw new IOException("Command response too large");}String output=bytes.toString("UTF-8");if(p.exitValue()!=0||output.contains("Error:")||output.contains("Exception"))throw new IOException("Android rejected operation: "+output.substring(0,Math.min(output.length(),300)));return output;}
    private List<Object> tasks()throws Exception {
        Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        return (List<Object>)Class.forName("android.app.IActivityTaskManager").getMethod("getAllRootTaskInfos").invoke(service);
    }
    private int number(Object task,String name)throws Exception{return task.getClass().getField(name).getInt(task);}
    private String taskPackage(Object task)throws Exception{Object top=task.getClass().getField("topActivity").get(task);return top instanceof ComponentName?((ComponentName)top).getPackageName():"";}
    private boolean visible(Object task){for(String name:new String[]{"isVisible","visible"})try{return task.getClass().getField(name).getBoolean(task);}catch(Exception ignored){}return true;}
    private JSONObject launch(JSONObject body)throws Exception {
        unlocked();create();String pkg=body.getString("package");if(!pkg.matches("[A-Za-z0-9_.]+"))throw new IllegalArgumentException("Invalid package");
        for(Object task:tasks())if(number(task,"displayId")==0&&visible(task)&&pkg.equals(taskPackage(task)))throw new IllegalStateException("This app is already open on the main display. Choose another app or close its foreground task before using the background screen.");
        String resolved=command("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER",pkg);String component=null;
        for(String line:resolved.split("\n"))if(line.trim().matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))component=line.trim();
        if(component==null)throw new IllegalArgumentException("App has no launcher activity");
        command("am","start","--display",Integer.toString(displayId),"-f","0x18800000","-n",component);packageName=pkg;return status().put("ok",true);
    }
    private Object resultTask(int expected,String wanted)throws Exception {Object fallback=null;for(Object task:tasks())if(number(task,"displayId")==displayId&&!taskPackage(task).isEmpty()&&(expected<0||number(task,"taskId")==expected)&&(wanted.isEmpty()||wanted.equals(taskPackage(task)))){if(expected>=0||visible(task))return task;if(fallback==null)fallback=task;}if(fallback==null)throw new IllegalStateException("The result page is no longer on the background screen");return fallback;}
    private JSONObject handoff(JSONObject body)throws Exception {
        unlocked();if(displayId<0)throw new IllegalStateException("No background screen");
        Object chosen=resultTask(body.optInt("task_id",-1),body.optString("package",packageName));int taskId=number(chosen,"taskId");String pkg=taskPackage(chosen);
        Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Class.forName("android.app.IActivityTaskManager").getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(service,taskId,0);
        command("am","task","focus",Integer.toString(taskId));packageName="";return new JSONObject().put("ok",true).put("package",pkg).put("task_id",taskId);
    }
    private JSONObject route(String route,JSONObject body)throws Exception {
        if(route.equals("/status"))return status();
        if(route.equals("/create"))return create();
        if(route.equals("/launch"))return launch(body);
        if(route.equals("/handoff"))return handoff(body);
        if(route.equals("/result")){unlocked();if(displayId<0)throw new IllegalStateException("No background screen");Object task=resultTask(-1,body.optString("package",packageName));return new JSONObject().put("task_id",number(task,"taskId")).put("package",taskPackage(task));}
        if(route.equals("/key")){unlocked();if(displayId<0)throw new IllegalStateException("No background display");String key=body.getString("key");if(!key.equals("back"))throw new IllegalArgumentException("Only back is supported in background mode; use launch to select another app");command("input","-d",Integer.toString(displayId),"keyevent","4");return new JSONObject().put("ok",true);}
        if(route.equals("/stop")){onMain(()->{releaseWake();screenWake=null;if(display!=null)display.release();if(reader!=null)reader.close();display=null;reader=null;displayId=-1;frame=null;packageName="";return null;});return status();}
        throw new IllegalArgumentException("Unknown display operation");
    }
    private void serve()throws IOException {ServerSocket server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",8766),8);ExecutorService clients=Executors.newFixedThreadPool(4);
        new Thread(()->{while(true)try{Socket client=server.accept();client.setSoTimeout(8000);clients.execute(()->handle(client));}catch(IOException ignored){}},"asl-background-http").start();System.out.println("ASL background worker ready");}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int ch;while((ch=in.read())!=-1){if(ch=='\n')break;if(ch!='\r')out.write(ch);if(out.size()>8192)throw new IOException("Header too large");}return out.toString("UTF-8");}
    private void handle(Socket client){try(Socket socket=client){InputStream input=new BufferedInputStream(socket.getInputStream());OutputStream output=socket.getOutputStream();String[] request=line(input).split(" ");if(request.length<2)return;Map<String,String> headers=new HashMap<>();int total=0;for(String header;!(header=line(input)).isEmpty();){total+=header.length();if(total>16384)throw new IOException("Headers too large");int at=header.indexOf(':');if(at>0)headers.put(header.substring(0,at).toLowerCase(Locale.ROOT),header.substring(at+1).trim());}
            if(!request[0].equals("POST")||!MessageDigest.isEqual(authorization,headers.getOrDefault("authorization","").getBytes(StandardCharsets.UTF_8))){reply(output,401,"application/json","{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8));return;}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length<0||length>65536)throw new IOException("Body too large");byte[] data=new byte[length];new DataInputStream(input).readFully(data);JSONObject body=new JSONObject(length==0?"{}":new String(data,StandardCharsets.UTF_8));
            try{if(request[1].equals("/frame")){unlocked();byte[] latest=frame;if(latest==null)throw new IllegalStateException("Background image not ready");reply(output,200,"image/jpeg",latest);}else{JSONObject result=actions.submit(()->route(request[1],body)).get(12,TimeUnit.SECONDS);reply(output,200,"application/json",result.toString().getBytes(StandardCharsets.UTF_8));}}
            catch(Exception e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();reply(output,409,"application/json",new JSONObject().put("error",String.valueOf(cause.getMessage())).toString().getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){}}
    private static void reply(OutputStream out,int code,String type,byte[] data)throws IOException{out.write(("HTTP/1.1 "+code+" OK\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();}
}
