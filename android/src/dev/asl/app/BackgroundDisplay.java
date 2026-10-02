package dev.asl.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.view.Display;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class BackgroundDisplay {
    private static Context context;
    private static String token;
    private static volatile boolean background;
    private static volatile int id=-1;
    static void initialize(Context app,String secret){context=app;token=secret;background=app.getSharedPreferences("asl",0).getBoolean("background_control",false);if(background)new Thread(()->{try{select("background");}catch(Exception ignored){}},"asl-display-connect").start();}
    static boolean selected(){return background;}
    static int displayId(){if(!background)return 0;if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("请先解锁平板再控制后台屏幕");if(id<1||context.getSystemService(DisplayManager.class).getDisplay(id)==null)throw new IllegalStateException("后台屏幕未连接。请通过 USB 激活后台组件，或在 ASL 中选择前台控制。");return id;}
    static Point size(){Display display=context.getSystemService(DisplayManager.class).getDisplay(displayId());if(display==null)throw new IllegalStateException("控制屏幕已关闭");Point size=new Point();display.getRealSize(size);return size;}
    static JSONObject select(String mode)throws Exception{if(!mode.equals("foreground")&&!mode.equals("background"))throw new IllegalArgumentException("mode must be foreground or background");if(mode.equals("background")){JSONObject info=request("/create",new JSONObject());id=info.getInt("display_id");if(id<1)throw new IOException("无法建立后台屏幕");}background=mode.equals("background");context.getSharedPreferences("asl",0).edit().putBoolean("background_control",background).apply();return status();}
    static JSONObject status()throws JSONException{JSONObject info;try{info=request("/status",new JSONObject());id=info.optInt("display_id",-1);}catch(Exception e){info=new JSONObject().put("available",false).put("error","后台组件未连接，请通过 USB 激活");id=-1;}return info.put("selected_mode",background?"background":"foreground");}
    static JSONObject request(String route,JSONObject body)throws Exception{return new JSONObject(new String(exchange(route,body),StandardCharsets.UTF_8));}
    static byte[] frame()throws Exception{return exchange("/frame",new JSONObject());}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int b;(b=in.read())!=-1;){if(b=='\n')return out.toString("US-ASCII");if(b!='\r')out.write(b);if(out.size()>8192)throw new IOException("Header too large");}throw new EOFException();}
    private static byte[] exchange(String route,JSONObject body)throws Exception{
        byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket()){socket.setTcpNoDelay(true);socket.connect(new InetSocketAddress("127.0.0.1",8766),600);socket.setSoTimeout(15000);OutputStream out=socket.getOutputStream();out.write(("POST "+route+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();InputStream in=new BufferedInputStream(socket.getInputStream());String first=line(in);int length=-1;for(int count=0;count<64;count++){String header=line(in);if(header.isEmpty())break;if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());}if(length<0||length>4*1024*1024)throw new IOException("Invalid display response");byte[] response=new byte[length];new DataInputStream(in).readFully(response);if(!first.contains(" 200 "))throw new IOException(new JSONObject(new String(response,StandardCharsets.UTF_8)).optString("error","后台操作失败"));return response;}
    }
    static AndroidControlService.Screenshot screenshot()throws Exception{byte[] data=frame();Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);if(bitmap==null)throw new IOException("后台画面尚未就绪");ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);int w=bitmap.getWidth(),h=bitmap.getHeight();bitmap.recycle();Point physical=size();return new AndroidControlService.Screenshot(bytes.toByteArray(),physical.x,physical.y,w,h);}
    static JSONObject finish(String title,String summary)throws Exception {
        if(!background)throw new IllegalStateException("请先选择后台虚拟屏幕");String pkg=AndroidControlService.current==null?"":AndroidControlService.current.foreground();if(pkg.isEmpty())throw new IllegalStateException("当前后台屏幕没有可确认的结果应用");JSONObject result=request("/result",new JSONObject().put("package",pkg));int task=result.getInt("task_id");
        NotificationManager manager=context.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("asl-results","Codex 任务完成",NotificationManager.IMPORTANCE_HIGH));
        PendingIntent preview=PendingIntent.getActivity(context,task,new Intent(context,PreviewActivity.class).putExtra("result_task",task).putExtra("result_package",pkg),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(context,task+10000,new Intent(context,PreviewActivity.class).putExtra("result_task",task).putExtra("result_package",pkg).putExtra("handoff",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.notify(1002,new Notification.Builder(context,"asl-results").setSmallIcon(context.getResources().getIdentifier("ic_terminal_status","drawable",context.getPackageName())).setContentTitle("Codex 已完成："+title).setContentText(summary).setStyle(new Notification.BigTextStyle().bigText(summary)).setContentIntent(preview).addAction(new Notification.Action.Builder(null,"查看结果",preview).build()).addAction(new Notification.Action.Builder(null,"打开原应用",open).build()).setAutoCancel(true).build());
        boolean posted=false;for(int attempt=0;attempt<8;attempt++){for(android.service.notification.StatusBarNotification notification:manager.getActiveNotifications())if(notification.getId()==1002)posted=true;if(posted)break;Thread.sleep(100);}
        return result.put("notified",posted).put("notifications_enabled",manager.areNotificationsEnabled());
    }
}
