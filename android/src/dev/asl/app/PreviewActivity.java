package dev.asl.app;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

public class PreviewActivity extends Activity {
    private final Handler handler=new Handler();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ImageView screen;private TextView status;private Bitmap previous;private boolean visible,loading;
    private final Runnable refresh=new Runnable(){public void run(){if(!visible)return;if(!loading){loading=true;worker.execute(()->{try{byte[] data=BackgroundDisplay.frame();Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);handler.post(()->{if(!visible){if(bitmap!=null)bitmap.recycle();return;}if(bitmap!=null){screen.setImageBitmap(bitmap);if(previous!=null)previous.recycle();previous=bitmap;status.setText("后台结果预览 · 打开原应用时，部分应用会重新加载页面");}});}catch(Exception e){handler.post(()->status.setText(e.getMessage()));}finally{handler.post(()->loading=false);}});}handler.postDelayed(this,250);}};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(0xff202020);getWindow().setNavigationBarColor(0xff151515);getWindow().setDecorFitsSystemWindows(false);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff151515);page.setPadding(12,12,12,12);
        page.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left+12,bars.top+12,bars.right+12,bars.bottom+12);return insets;});
        LinearLayout bar=new LinearLayout(this);Button back=new Button(this);back.setText("返回终端");back.setOnClickListener(v->finish());bar.addView(back);TextView title=new TextView(this);title.setText("Codex 后台屏幕");title.setTextColor(0xffeeeeee);title.setTextSize(18);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,-1,1));Button take=new Button(this);take.setText("打开原应用");take.setOnClickListener(v->handoff());bar.addView(take);page.addView(bar);
        screen=new ImageView(this);screen.setScaleType(ImageView.ScaleType.FIT_CENTER);page.addView(screen,new LinearLayout.LayoutParams(-1,0,1));status=new TextView(this);status.setTextColor(0xffaaaaaa);status.setPadding(12,8,12,8);page.addView(status);setContentView(page);
        if(getIntent().getBooleanExtra("handoff",false))handoff();
    }
    private void handoff(){worker.execute(()->{try{JSONObject body=new JSONObject();int task=getIntent().getIntExtra("result_task",-1);String pkg=getIntent().getStringExtra("result_package");if(task>=0)body.put("task_id",task);if(pkg==null&&AndroidControlService.current!=null)pkg=AndroidControlService.current.foreground();if(pkg!=null&&!pkg.isEmpty())body.put("package",pkg);BackgroundDisplay.request("/handoff",body);handler.post(this::finish);}catch(Exception e){handler.post(()->new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("知道了",null).show());}});}
    @Override protected void onStart(){super.onStart();visible=true;handler.post(refresh);}
    @Override protected void onStop(){visible=false;handler.removeCallbacks(refresh);super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();screen.setImageDrawable(null);if(previous!=null)previous.recycle();super.onDestroy();}
}
