package dev.asl.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.*;
import android.os.*;
import android.view.Display;
import android.view.accessibility.*;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

public class AndroidControlService extends AccessibilityService {
    public static volatile AndroidControlService current;
    private final Map<String,NodeRef> nodes=new LinkedHashMap<>();
    private final Map<String,ShotRef> shots=new LinkedHashMap<>();
    private volatile long lastWindowEvent;
    @Override protected void onServiceConnected(){current=this;VmService.append("\r\n[ASL] Android 无障碍控制已连接。\r\n");}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)lastWindowEvent=SystemClock.elapsedRealtime();}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){current=null;synchronized(nodes){for(NodeRef ref:nodes.values())ref.node.recycle();nodes.clear();}super.onDestroy();}

    private static String value(CharSequence s){return s==null?"":s.toString();}
    private AccessibilityNodeInfo activeRoot(){int id=BackgroundDisplay.displayId();if(id==0)return getRootInActiveWindow();android.util.SparseArray<java.util.List<AccessibilityWindowInfo>> displays=getWindowsOnAllDisplays();java.util.List<AccessibilityWindowInfo> windows=displays.get(id);AccessibilityNodeInfo root=null;int best=-1;if(windows!=null)for(AccessibilityWindowInfo window:windows){if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;int score=(window.isActive()?100000:0)+(window.isFocused()?10000:0)+window.getLayer();if(score>best){AccessibilityNodeInfo next=window.getRoot();if(next!=null){if(root!=null)root.recycle();root=next;best=score;}}}for(int i=0;i<displays.size();i++)for(AccessibilityWindowInfo window:displays.valueAt(i))window.recycle();return root;}
    private static String identity(AccessibilityNodeInfo n){return value(n.getPackageName())+"|"+value(n.getClassName())+"|"+n.getViewIdResourceName()+"|"+(n.isPassword()?"":value(n.getText()))+"|"+value(n.getContentDescription());}
    private static class NodeRef {
        final AccessibilityNodeInfo node;final String identity;final long created;
        NodeRef(AccessibilityNodeInfo n){node=AccessibilityNodeInfo.obtain(n);identity=identity(n);created=SystemClock.elapsedRealtime();}
    }
    private static class ShotRef {
        final int width,height,imageWidth,imageHeight,window;final String pkg;final long created;
        ShotRef(Screenshot s,int w,String p){width=s.width;height=s.height;imageWidth=s.imageWidth;imageHeight=s.imageHeight;window=w;pkg=p;created=SystemClock.elapsedRealtime();}
    }
    public String foreground(){AccessibilityNodeInfo root=activeRoot();if(root==null)return "";try{return value(root.getPackageName());}finally{root.recycle();}}
    public JSONObject settle(String expected,int timeout)throws Exception {
        long start=SystemClock.elapsedRealtime(),sameSince=start;String previous="";boolean stable=false;
        do {String pkg=foreground();long now=SystemClock.elapsedRealtime();if(!pkg.equals(previous)){previous=pkg;sameSince=now;}
            if(!pkg.isEmpty()&&(expected==null||expected.equals(pkg))&&now-sameSince>=250&&now-lastWindowEvent>=180){stable=true;break;}
            Thread.sleep(60);
        }while(SystemClock.elapsedRealtime()-start<timeout);
        return new JSONObject().put("foreground_package",previous).put("settled",stable).put("elapsed_ms",SystemClock.elapsedRealtime()-start);
    }
    public void checkPackage(JSONObject body){String expected=body.optString("expected_package","");if(!expected.isEmpty()&&!expected.equals(foreground()))throw new IllegalStateException("Foreground app changed; read the UI again");}

    public JSONObject tree() throws Exception {
        AccessibilityNodeInfo root=activeRoot();
        JSONObject result=new JSONObject();
        if(root==null)return result.put("error","没有可读取的活动窗口");
        synchronized(nodes){long cutoff=SystemClock.elapsedRealtime()-60000;Iterator<NodeRef> refs=nodes.values().iterator();while(refs.hasNext()){NodeRef ref=refs.next();if(ref.created<cutoff){ref.node.recycle();refs.remove();}}while(nodes.size()>1800){String key=nodes.keySet().iterator().next();nodes.remove(key).node.recycle();}}
        try {String snapshot=UUID.randomUUID().toString().substring(0,8);int[] budget={0};
            result.put("foreground_package",value(root.getPackageName())).put("window_id",root.getWindowId()).put("display_id",BackgroundDisplay.displayId()).put("coordinate_space","selected_display_pixels")
                .put("root",node(root,snapshot+":0",budget,0)).put("node_count",budget[0]).put("truncated",budget[0]>=600);
            if(budget[0]<15)result.put("hint","This app may draw custom controls. Use a screenshot and pass screenshot_id to tap/swipe.");return result;
        }finally{root.recycle();}
    }
    private JSONObject node(AccessibilityNodeInfo n,String path,int[] count,int depth) throws Exception {
        count[0]++;Rect r=new Rect();n.getBoundsInScreen(r);
        synchronized(nodes){nodes.put(path,new NodeRef(n));}
        JSONObject out=new JSONObject().put("id",path).put("class",String.valueOf(n.getClassName()))
            .put("package",String.valueOf(n.getPackageName())).put("bounds",new JSONArray(new int[]{r.left,r.top,r.right,r.bottom}))
            .put("clickable",n.isClickable()).put("editable",n.isEditable()).put("enabled",n.isEnabled()).put("focused",n.isFocused())
            .put("visible",n.isVisibleToUser()).put("scrollable",n.isScrollable()).put("selected",n.isSelected()).put("checked",n.isChecked()).put("checkable",n.isCheckable()).put("password",n.isPassword())
            .put("text",n.isPassword()?"<password>":String.valueOf(n.getText()==null?"":n.getText()))
            .put("description",String.valueOf(n.getContentDescription()==null?"":n.getContentDescription()));
        if(n.getViewIdResourceName()!=null)out.put("resource_id",n.getViewIdResourceName());
        JSONArray children=new JSONArray();
        if(depth<35&&count[0]<600)for(int i=0;i<n.getChildCount()&&count[0]<600;i++){
            AccessibilityNodeInfo child=n.getChild(i);if(child!=null){if(child.isVisibleToUser())children.put(node(child,path+"."+i,count,depth+1));child.recycle();}
        }
        out.put("children",children);return out;
    }
    private AccessibilityNodeInfo resolve(String id) {
        AccessibilityNodeInfo n;String old;
        synchronized(nodes){NodeRef ref=nodes.get(id);if(ref==null||SystemClock.elapsedRealtime()-ref.created>60000)throw new IllegalStateException("Node expired; read the UI tree again");n=AccessibilityNodeInfo.obtain(ref.node);old=ref.identity;}
        AccessibilityNodeInfo root=activeRoot();
        try {
            Rect bounds=new Rect();n.getBoundsInScreen(bounds);
            if(root==null||root.getWindowId()!=n.getWindowId()||!n.refresh()||!n.isVisibleToUser()||!n.isEnabled()||!old.equals(identity(n)))throw new IllegalStateException("UI changed; read the tree again before acting");
            return n;
        }catch(RuntimeException e){n.recycle();throw e;}finally{if(root!=null)root.recycle();}
    }
    public JSONObject nodeAction(JSONObject body)throws Exception {
        checkPackage(body);AccessibilityNodeInfo n=resolve(body.getString("node_id"));String action=body.getString("action");boolean ok=false;String method="accessibility";
        try {
            if(action.equals("set_text")){
                if(!n.isEditable()||n.isPassword())throw new IllegalStateException("Select a non-password editable node");
                String text=body.getString("text");if(text.length()>10000)throw new IllegalArgumentException("Text too long");
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
                ok=n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
                if(ok&&n.refresh())ok=text.equals(value(n.getText()));
            }else if(action.equals("scroll")){
                String direction=body.optString("direction","down");int id;
                switch(direction){case "up":id=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();break;case "down":id=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();break;case "left":id=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();break;case "right":id=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();break;default:throw new IllegalArgumentException("Invalid direction");}
                ok=n.performAction(id);if(!ok)ok=n.performAction(direction.equals("up")||direction.equals("left")?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }else if(action.equals("focus")){ok=n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }else if(action.equals("submit")){ok=n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }else if(action.equals("click")||action.equals("long_click")){
                int which=action.equals("click")?AccessibilityNodeInfo.ACTION_CLICK:AccessibilityNodeInfo.ACTION_LONG_CLICK;
                AccessibilityNodeInfo target=AccessibilityNodeInfo.obtain(n);
                try {for(int i=0;i<5;i++){if(target.isClickable()||target.isLongClickable()){ok=target.performAction(which);break;}AccessibilityNodeInfo parent=target.getParent();if(parent==null)break;target.recycle();target=parent;}}finally{target.recycle();}
                if(!ok){Rect bounds=new Rect();n.getBoundsInScreen(bounds);android.graphics.Point size=BackgroundDisplay.size();
                    if(!bounds.intersect(0,0,size.x,size.y)||bounds.width()<2||bounds.height()<2)throw new IllegalStateException("Node is outside the screen");
                    method="gesture";ok=gesture(bounds.centerX(),bounds.centerY(),bounds.centerX(),bounds.centerY(),action.equals("click")?70:650);}
            }else throw new IllegalArgumentException("Unknown node action");
        }finally{n.recycle();}
        if(!ok)throw new IllegalStateException("App did not accept "+action+"; inspect a fresh tree or screenshot");
        return settle(null,1000).put("ok",true).put("method",method).put("action",action);
    }
    public String remember(Screenshot screenshot) {
        AccessibilityNodeInfo root=activeRoot();String id=UUID.randomUUID().toString();
        try{synchronized(shots){while(shots.size()>=8)shots.remove(shots.keySet().iterator().next());shots.put(id,new ShotRef(screenshot,root==null?-1:root.getWindowId(),root==null?"":value(root.getPackageName())));}}finally{if(root!=null)root.recycle();}return id;
    }
    public float[] point(JSONObject body,String xKey,String yKey,Point size)throws Exception {
        checkPackage(body);float x=(float)body.getDouble(xKey),y=(float)body.getDouble(yKey);String id=body.optString("screenshot_id","");
        if(!id.isEmpty()){ShotRef ref;synchronized(shots){ref=shots.get(id);}AccessibilityNodeInfo root=activeRoot();
            try {if(ref==null||SystemClock.elapsedRealtime()-ref.created>60000||ref.width!=size.x||ref.height!=size.y||root==null||root.getWindowId()!=ref.window||!ref.pkg.equals(value(root.getPackageName())))throw new IllegalStateException("Screenshot expired or app/rotation changed; capture again");
                if(x<0||y<0||x>=ref.imageWidth||y>=ref.imageHeight)throw new IllegalArgumentException("Coordinates outside screenshot");x=x*ref.width/ref.imageWidth;y=y*ref.height/ref.imageHeight;
            }finally{if(root!=null)root.recycle();}}
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=size.x||y>=size.y)throw new IllegalArgumentException("Coordinates outside Android display "+size.x+"x"+size.y);
        return new float[]{x,y};
    }
    public boolean text(String value) {
        AccessibilityNodeInfo root=activeRoot();if(root==null)return false;
        AccessibilityNodeInfo focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(focus==null){root.recycle();return false;}
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        boolean ok=focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);focus.recycle();root.recycle();return ok;
    }
    public boolean gesture(float x1,float y1,float x2,float y2,long ms) throws Exception {
        int targetDisplay=BackgroundDisplay.displayId();
        Path path=new Path();path.moveTo(x1,y1);if(x1!=x2||y1!=y2)path.lineTo(x2,y2);
        CountDownLatch latch=new CountDownLatch(1);boolean[] result={false};
        new Handler(getMainLooper()).post(()->{
            boolean accepted=dispatchGesture(new GestureDescription.Builder().setDisplayId(targetDisplay).addStroke(new GestureDescription.StrokeDescription(path,0,ms)).build(),
                new GestureResultCallback(){public void onCompleted(GestureDescription g){result[0]=true;latch.countDown();}
                    public void onCancelled(GestureDescription g){latch.countDown();}},null);
            if(!accepted)latch.countDown();
        });
        if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Gesture timeout");return result[0];
    }
    public Screenshot capture(int maxWidth) throws Exception {
        if(BackgroundDisplay.selected())return BackgroundDisplay.screenshot();
        CountDownLatch latch=new CountDownLatch(1);Screenshot[] result={null};String[] error={null};
        takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            public void onSuccess(ScreenshotResult shot){
                try {
                    Bitmap hardware=Bitmap.wrapHardwareBuffer(shot.getHardwareBuffer(),shot.getColorSpace());
                    if(hardware==null)throw new IllegalStateException("Screenshot buffer unavailable");
                    Bitmap bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();shot.getHardwareBuffer().close();
                    int originalWidth=bitmap.getWidth(),originalHeight=bitmap.getHeight();
                    if(bitmap.getWidth()>maxWidth){Bitmap scaled=Bitmap.createScaledBitmap(bitmap,maxWidth,(int)((long)bitmap.getHeight()*maxWidth/bitmap.getWidth()),true);bitmap.recycle();bitmap=scaled;}
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);
                    result[0]=new Screenshot(bytes.toByteArray(),originalWidth,originalHeight,bitmap.getWidth(),bitmap.getHeight());bitmap.recycle();
                }catch(Exception e){error[0]=e.getMessage();}finally{latch.countDown();}
            }
            public void onFailure(int code){error[0]="Android screenshot error "+code;latch.countDown();}
        });
        if(!latch.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Screenshot timeout");
        if(error[0]!=null)throw new IllegalStateException(error[0]);return result[0];
    }
    public static class Screenshot {
        public final byte[] png;public final int width,height,imageWidth,imageHeight;
        Screenshot(byte[] png,int w,int h,int iw,int ih){this.png=png;width=w;height=h;imageWidth=iw;imageHeight=ih;}
    }
}
