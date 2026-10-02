package dev.asl.app;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public class MainActivity extends Activity {
    private final Handler handler=new Handler();
    private TextView status,resources,start;
    private WebView terminal;
    private LinearLayout tabBar;
    private String renderedTabs="";
    private boolean terminalPageReady;
    private volatile long visibleSince;
    private int consumedEscapeKey=-1;
    private final AtomicBoolean outputQueued=new AtomicBoolean();
    private final Runnable pushOutput=()->{outputQueued.set(false);if(terminalPageReady&&terminal!=null)terminal.evaluateJavascript("window.aslRefresh()",null);renderTabs();};
    private final Runnable outputChanged=()->{if(outputQueued.compareAndSet(false,true))handler.postDelayed(pushOutput,16);};
    private final Runnable refresh=new Runnable(){public void run(){
        boolean ready=VmService.imageReady(MainActivity.this);
        status.setText((ready||VmService.status.startsWith("正在导入")?VmService.status:"系统镜像尚未导入")+"  ·  Android "+(AndroidControlService.current!=null?"已连接":"未授权")+" · "+(BackgroundDisplay.selected()?"后台屏幕":"前台屏幕"));
        int memory=getSharedPreferences("asl",0).getInt("memory_mb",4096);
        resources.setText("2C / "+(memory/1024)+"G");start.setText(ready?"▶ 启动":"导入系统");
        handler.postDelayed(this,1000);
    }};
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
    private TextView action(String label,Runnable click){TextView v=new TextView(this);v.setText(label);v.setTextSize(13);v.setTextColor(0xffc9c9c9);v.setGravity(Gravity.CENTER);v.setPadding(dp(15),dp(13),dp(15),dp(13));v.setOnClickListener(w->click.run());return v;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(0xff202020);getWindow().setNavigationBarColor(0xff151515);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff151515);
        page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;
        });
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackgroundColor(0xff272727);bar.setPadding(dp(14),0,dp(4),0);
        TextView title=action("›_  ASL",this::focusTerminal);title.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);title.setTextColor(0xffeeeeee);title.setTextSize(18);
        bar.addView(title,new LinearLayout.LayoutParams(-2,-2));
        TextView plus=action("+",()->{command("dev.asl.NEW_TERMINAL");focusTerminal();});plus.setTextSize(23);plus.setContentDescription("新增终端");bar.addView(plus);
        bar.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        resources=action("2C / 4G",this::chooseMemory);bar.addView(resources);
        start=action("▶ 启动",()->{if(VmService.imageReady(this))command(VmService.START);else importImage();});bar.addView(start);
        bar.addView(action("关机",()->command(VmService.SHUTDOWN)));
        bar.addView(action("⋮",()->showMenu(bar)));page.addView(bar);
        HorizontalScrollView tabScroll=new HorizontalScrollView(this);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.setBackgroundColor(0xff202020);
        tabBar=new LinearLayout(this);tabScroll.addView(tabBar);page.addView(tabScroll);tabBar.setVisibility(View.GONE);
        terminal=new WebView(this);terminal.setBackgroundColor(0xff151515);
        WebSettings settings=terminal.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
        terminal.addJavascriptInterface(new TerminalBridge(),"ASL");
        terminal.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public void onPageFinished(WebView view,String url){terminalPageReady=true;focusTerminal();outputChanged.run();}
        });
        terminal.loadUrl("file:///android_asset/terminal.html");page.addView(terminal,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setTextColor(0xff9a9a9a);status.setBackgroundColor(0xff202020);status.setTextSize(11);status.setPadding(dp(12),dp(5),dp(12),dp(5));page.addView(status);
        setContentView(page);
        page.requestApplyInsets();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
        command(VmService.BRIDGE);
    }
    private void showMenu(View anchor){PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("ASLTree 操作记忆").setOnMenuItemClickListener(i->{startActivity(new Intent(this,OperationLibraryActivity.class));return true;});
        menu.getMenu().add("Android 控制屏幕").setOnMenuItemClickListener(i->{chooseDisplay();return true;});
        menu.getMenu().add("查看 Codex 后台画面").setOnMenuItemClickListener(i->{startActivity(new Intent(this,PreviewActivity.class));return true;});
        menu.getMenu().add("Android 控制授权").setOnMenuItemClickListener(i->{accessibility();return true;});
        menu.getMenu().add("后台运行设置").setOnMenuItemClickListener(i->{backgroundSettings();return true;});
        menu.getMenu().add("导入系统镜像").setOnMenuItemClickListener(i->{importImage();return true;});
        menu.getMenu().add("检测虚拟机引擎").setOnMenuItemClickListener(i->{command(VmService.VERSION);return true;});menu.show();
    }
    private void chooseDisplay(){String[] modes={"前台屏幕：操作平板当前界面","后台虚拟屏幕：主屏可继续使用"};new AlertDialog.Builder(this).setTitle("Codex 使用哪块屏幕").setSingleChoiceItems(modes,BackgroundDisplay.selected()?1:0,(dialog,which)->{dialog.dismiss();new Thread(()->{try{BackgroundDisplay.select(which==1?"background":"foreground");handler.post(()->Toast.makeText(this,which==1?"已选择后台屏幕，可从菜单查看画面":"已选择前台屏幕",Toast.LENGTH_LONG).show());}catch(Exception e){handler.post(()->new AlertDialog.Builder(this).setTitle("后台组件未连接").setMessage("首次使用或平板重启后，需要通过 USB 调试激活 ASL 后台组件。当前屏幕选择未改变。").setPositiveButton("知道了",null).show());}},"asl-select-display").start();}).setNegativeButton("取消",null).show();}
    private void backgroundSettings(){new AlertDialog.Builder(this).setTitle("允许 ASL 持续运行").setMessage("切换到其他应用时，Linux 和 Android 控制仍需运行。请在系统的省电策略中为 ASL 选择“无限制”。小米平板也建议在最近任务中锁定 ASL。").setPositiveButton("打开应用设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNeutralButton("允许后台运行",(d,w)->{try{startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}catch(Exception e){startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}}).setNegativeButton("取消",null).show();}
    private void accessibility(){command(VmService.BRIDGE);
        Intent detail=new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME,new ComponentName(this,AndroidControlService.class));
        try{startActivity(detail);}catch(ActivityNotFoundException|SecurityException e){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
    }
    private void chooseMemory(){String[] names={"2 核 · 1 GB","2 核 · 2 GB","2 核 · 3 GB","2 核 · 4 GB"};int current=getSharedPreferences("asl",0).getInt("memory_mb",4096)/1024-1;
        new AlertDialog.Builder(this).setTitle("Linux 内存上限").setSingleChoiceItems(names,current,(dialog,which)->{
            getSharedPreferences("asl",0).edit().putInt("memory_mb",(which+1)*1024).apply();dialog.dismiss();Toast.makeText(this,"下次启动 Linux 时生效",Toast.LENGTH_SHORT).show();
        }).setNegativeButton("取消",null).show();
    }
    private void importImage(){if(VmService.imageReady(this)){new AlertDialog.Builder(this).setMessage("已安装系统。为保留现有 Linux 数据，此版本不覆盖已有磁盘。").setPositiveButton("知道了",null).show();return;}
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),2);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==2&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startForegroundService(new Intent(this,VmService.class).setAction(VmService.IMPORT).putExtra("uri",uri.toString()));}
    }
    private void command(String action){startForegroundService(new Intent(this,VmService.class).setAction(action));if(VmService.START.equals(action)&&terminal!=null){terminal.requestFocus();terminal.evaluateJavascript("window.aslFocus()",null);}}
    private void focusTerminal(){if(terminal!=null){terminal.requestFocus();if(terminalPageReady)terminal.evaluateJavascript("window.aslFocus()",null);}}
    private void renderTabs(){if(tabBar==null||renderedTabs.equals(VmService.terminalWindows))return;renderedTabs=VmService.terminalWindows;
        tabBar.removeAllViews();try{JSONArray windows=new JSONArray(renderedTabs);tabBar.setVisibility(windows.length()==0?View.GONE:View.VISIBLE);
            for(int i=0;i<windows.length();i++){JSONObject window=windows.getJSONObject(i);String id=window.getString("id");String title=window.getString("name")+("已结束".equals(window.optString("state"))?" · 已结束":"");
                LinearLayout group=new LinearLayout(this);group.setBackgroundColor(window.optBoolean("active")?0xff3b3b3b:0xff202020);
                TextView tab=action(title,()->{TerminalSessions.select(id);terminal.evaluateJavascript("window.aslSelect("+JSONObject.quote(id)+")",null);renderTabs();focusTerminal();});
                tab.setTextSize(12);tab.setPadding(dp(15),dp(7),dp(15),dp(7));tab.setBackgroundColor(window.optBoolean("active")?0xff3b3b3b:0xff202020);
                tab.setContentDescription(title);group.addView(tab);if(window.optBoolean("closable")){TextView close=action("×",()->closeTerminal(id,title,false));close.setPadding(dp(10),dp(7),dp(12),dp(7));close.setContentDescription("关闭 "+title);group.addView(close);}tabBar.addView(group);
            }
        }catch(JSONException ignored){}
    }
    private void closeTerminal(String id,String title,boolean force){TerminalSessions.close(id,force,result->handler.post(()->{if(isFinishing()||isDestroyed())return;if(result.optBoolean("needs_confirmation"))new AlertDialog.Builder(this).setTitle("关闭 "+title+"？").setMessage("该终端正在运行 "+result.optString("command")+"。关闭会结束此终端中的任务。").setNegativeButton("取消",null).setPositiveButton("关闭终端",(d,w)->closeTerminal(id,title,true)).show();else if(result.has("error"))Toast.makeText(this,result.optString("error"),Toast.LENGTH_LONG).show();else outputChanged.run();}));}
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event){
        if(terminal!=null&&terminal.hasFocus()&&event.getAction()==KeyEvent.ACTION_DOWN&&event.isCtrlPressed()&&!event.isAltPressed()){
            int code=event.getKeyCode();
            if(event.isShiftPressed()&&code==KeyEvent.KEYCODE_C){terminal.evaluateJavascript("window.aslCopy()",null);return true;}
            if(event.isShiftPressed()&&code==KeyEvent.KEYCODE_V){android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
                if(clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip().getItemCount()>0){String text=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString();terminal.evaluateJavascript("window.aslPaste("+org.json.JSONObject.quote(text)+")",null);}return true;}
            if(!event.isShiftPressed()&&code>=KeyEvent.KEYCODE_A&&code<=KeyEvent.KEYCODE_Z){VmService.input(String.valueOf((char)(code-KeyEvent.KEYCODE_A+1)));return true;}
        }return super.dispatchKeyShortcutEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();InputDevice device=event.getDevice();
        boolean physicalKeyboard=device!=null&&event.getDeviceId()>=0&&device.getKeyboardType()==InputDevice.KEYBOARD_TYPE_ALPHABETIC;
        boolean terminalEscape=code==KeyEvent.KEYCODE_ESCAPE||(code==KeyEvent.KEYCODE_BACK&&physicalKeyboard);
        if(event.getAction()==KeyEvent.ACTION_UP&&code==consumedEscapeKey){consumedEscapeKey=-1;return true;}
        if(terminalEscape&&terminal!=null&&terminalPageReady&&hasWindowFocus()){
            if(event.getAction()==KeyEvent.ACTION_DOWN){consumedEscapeKey=code;VmService.input("\u001b");}
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onStart(){super.onStart();visibleSince=SystemClock.elapsedRealtime();handler.post(refresh);VmService.addOutputListener(outputChanged);outputChanged.run();}
    @Override protected void onStop(){handler.removeCallbacks(refresh);VmService.removeOutputListener(outputChanged);handler.removeCallbacks(pushOutput);outputQueued.set(false);super.onStop();}
    @Override protected void onDestroy(){terminalPageReady=false;if(terminal!=null)terminal.destroy();super.onDestroy();}
    private class TerminalBridge {
        @JavascriptInterface public String tabs(){return VmService.terminalWindows;}
        @JavascriptInterface public String poll(String id,long offset){return id.equals("console")?VmService.readLog(offset):TerminalSessions.poll(id,offset);}
        @JavascriptInterface public void input(String id,String text){VmService.input(id,text);}
        @JavascriptInterface public void resize(String id,int columns,int rows){if(columns>=20&&columns<=500&&rows>=5&&rows<=300){VmService.terminalColumns=columns;VmService.terminalRows=rows;if(!id.equals("console"))TerminalSessions.resize(id,columns,rows);}}
        @JavascriptInterface public void rendered(String id,long received){TerminalSessions.rendered(id,received>=visibleSince?received:0);}
        @JavascriptInterface public void copy(String text){if(!text.isEmpty())getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("ASL terminal",text));}
    }
}
