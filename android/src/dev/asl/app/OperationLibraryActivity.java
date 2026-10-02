package dev.asl.app;

import android.app.Activity;
import android.graphics.Insets;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

public class OperationLibraryActivity extends Activity {
    private final Handler handler=new Handler();private LinearLayout list;private TextView status;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(0xff202020);getWindow().setNavigationBarColor(0xff151515);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff151515);page.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left+20,bars.top,bars.right+20,bars.bottom);return insets;});
        LinearLayout bar=new LinearLayout(this);Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->finish());bar.addView(back);TextView title=text("ASLTree 操作记忆",20);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,-1,1));Button refresh=new Button(this);refresh.setText("刷新");refresh.setOnClickListener(v->load());bar.addView(refresh);page.addView(bar);
        status=text("读取中…",14);page.addView(status);ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);load();
    }
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextColor(0xffdedede);t.setTextSize(size);t.setPadding(12,16,12,16);return t;}
    private void load(){status.setText("读取操作记忆…");new Thread(()->{try{JSONObject result=VmService.guestRequest("/memory/catalog",new JSONObject());handler.post(()->render(result));}catch(Exception e){handler.post(()->status.setText("请先启动 Linux，操作记忆保存在 Linux 中。"));}},"asl-operation-library").start();}
    private void render(JSONObject result){if(isFinishing()||isDestroyed())return;list.removeAllViews();JSONArray apps=result.optJSONArray("apps");if(apps==null||apps.length()==0){status.setText("还没有已学习的操作");list.addView(text("在 Codex 中让它学习一个常用操作，例如搜索内容，然后保存为可复用流程。输入词可以替换，每次执行都会检查结果。",16));return;}
        status.setText(apps.length()+" 个应用 · "+result.optInt("edge_count")+" 条已观察的页面路径");
        for(int i=0;i<apps.length();i++){JSONObject app=apps.optJSONObject(i);list.addView(text(app.optString("package")+" · "+app.optInt("states")+" 个页面状态",16));JSONArray skills=app.optJSONArray("skills");if(skills==null)continue;for(int j=0;j<skills.length();j++){JSONObject s=skills.optJSONObject(j);String state=s.optString("status");String label="ready".equals(state)?"可复用":"needs_review".equals(state)?"待检查":"stale".equals(state)?"应用已更新，需重新学习":"学习中";String description=s.optString("description");TextView item=text("  "+s.optString("name")+"  ·  "+label+"\n  "+s.optInt("step_count")+" 步 · 成功 "+s.optInt("successes")+" 次 · 未完成 "+s.optInt("failures")+" 次"+(description.isEmpty()?"":"\n  "+description),15);item.setBackgroundColor(0xff242424);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(16,0,16,10);list.addView(item,params);}}
    }
}
