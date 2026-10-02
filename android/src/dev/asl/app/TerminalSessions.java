package dev.asl.app;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** PTYs are persistent connections. Selecting a tab never waits for the guest. */
final class TerminalSessions {
    private static final Map<String,Session> sessions=new LinkedHashMap<>();
    private static final ExecutorService requests=Executors.newSingleThreadExecutor();
    private static String token="";
    private static int sequence;
    static volatile String active="console";
    private static class Session {
        final String id,title;final StringBuilder output=new StringBuilder();
        final ExecutorService writer=Executors.newSingleThreadExecutor();
        final CountDownLatch connected=new CountDownLatch(1);
        long base,lastOutput,reads,characters,rendered,maxRenderDelay;int columns=120,rows=40;
        volatile String remote="",state="连接中";volatile Socket socket;volatile boolean closed;
        Session(String id,String title){this.id=id;this.title=title;}
        synchronized void append(String text){output.append(text);reads++;characters+=text.length();lastOutput=SystemClock.elapsedRealtime();if(output.length()>2000000){int remove=output.length()-1600000;if(Character.isLowSurrogate(output.charAt(remove)))remove++;output.delete(0,remove);base+=remove;}VmService.signalOutput();}
        synchronized String poll(long cursor){try{int start=(int)Math.max(0,Math.min(output.length(),cursor-base));int end=Math.min(output.length(),start+65536);if(end<output.length()&&Character.isHighSurrogate(output.charAt(end-1)))end--;return new JSONObject().put("text",output.substring(start,end)).put("next",base+end).put("more",end<output.length()).put("truncated",cursor<base).put("received_ms",lastOutput).toString();}catch(JSONException e){return "{}";}}
        void send(int kind,byte[] data){if(closed)return;try{writer.execute(()->{try{if(!connected.await(15,TimeUnit.SECONDS)||closed)return;Socket s=socket;if(s==null||remote.isEmpty())return;DataOutputStream out=new DataOutputStream(s.getOutputStream());if(kind==1){for(int start=0;start<data.length;start+=65536){int length=Math.min(65536,data.length-start);out.writeByte(1);out.writeInt(length);out.write(data,start,length);}}else{out.writeByte(kind);out.writeInt(data.length);out.write(data);}out.flush();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){disconnect();}});}catch(RejectedExecutionException ignored){}}
        void disconnect(){closed=true;connected.countDown();state="已结束";try{if(socket!=null)socket.close();}catch(IOException ignored){}writer.shutdownNow();publish();}
    }
    static void initialize(String secret){token=secret;publish();}
    static synchronized boolean hasLive(){for(Session s:sessions.values())if(!s.closed)return true;return false;}
    static synchronized void select(String id){if(!id.equals("console")&&!sessions.containsKey(id))return;active=id;publish();}
    static synchronized String create(){if(sessions.size()>=16)return null;String id="t"+(++sequence);Session s=new Session(id,"终端 "+sequence);s.columns=VmService.terminalColumns;s.rows=VmService.terminalRows;sessions.put(id,s);active=id;publish();new Thread(()->connect(s),"asl-pty-"+id).start();return id;}
    private static void connect(Session s){
        try{Socket socket=new Socket();s.socket=socket;socket.setTcpNoDelay(true);socket.connect(new InetSocketAddress("127.0.0.1",2223),3000);socket.setSoTimeout(15000);
            byte[] body=new JSONObject().put("columns",s.columns).put("rows",s.rows).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();out.write(("POST /pty HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nContent-Length: "+body.length+"\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            // Keep the buffered stream when parsing headers: it may already contain PTY data.
            BufferedInputStream input=new BufferedInputStream(socket.getInputStream());String first=line(input);if(!first.contains(" 200 "))throw new IOException("Linux 终端未就绪，请稍后重试");
            for(int i=0;i<64;i++){String header=line(input);if(header.isEmpty())break;if(header.toLowerCase(Locale.ROOT).startsWith("x-asl-session:"))s.remote=header.substring(header.indexOf(':')+1).trim();}
            if(s.remote.isEmpty())throw new IOException("缺少终端会话编号");socket.setSoTimeout(0);s.state="运行中";s.connected.countDown();publish();
            InputStreamReader reader=new InputStreamReader(input,StandardCharsets.UTF_8);char[] buffer=new char[16384];int n;while(!s.closed&&(n=reader.read(buffer))!=-1)s.append(new String(buffer,0,n));
        }catch(Exception e){if(!s.closed)s.append("\r\n[ASL] "+e.getMessage()+"\r\n");}finally{s.disconnect();}
    }
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int ch;(ch=in.read())!=-1;){if(ch=='\n')return bytes.toString("US-ASCII");if(ch!='\r')bytes.write(ch);if(bytes.size()>8192)throw new IOException("Header too large");}throw new EOFException();}
    static synchronized String poll(String id,long cursor){Session s=sessions.get(id);return s==null?"{}":s.poll(cursor);}
    static synchronized void input(String id,String text){Session s=sessions.get(id);if(s!=null&&text.length()<=1048576)s.send(1,text.getBytes(StandardCharsets.UTF_8));}
    static synchronized void resize(String id,int columns,int rows){Session s=sessions.get(id);if(s==null||s.closed||columns<20||columns>500||rows<5||rows>300||s.columns==columns&&s.rows==rows)return;s.columns=columns;s.rows=rows;try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(bytes);data.writeInt(columns);data.writeInt(rows);s.send(2,bytes.toByteArray());}catch(IOException ignored){}}
    interface CloseResult{void done(JSONObject result);}
    static void close(String id,boolean force,CloseResult callback){requests.execute(()->{JSONObject result;try{Session s;synchronized(TerminalSessions.class){s=sessions.get(id);}if(s==null){result=new JSONObject().put("ok",true);}else{
            result=s.closed||s.remote.isEmpty()?new JSONObject().put("ok",true):VmService.guestRequest("/pty/close",new JSONObject().put("id",s.remote).put("force",force));
            if(result.optBoolean("ok")){s.disconnect();synchronized(TerminalSessions.class){sessions.remove(id);if(active.equals(id))active=sessions.isEmpty()?"console":sessions.keySet().iterator().next();}publish();}
        }}catch(Exception e){result=new JSONObject();try{result.put("error",e.getMessage());}catch(JSONException ignored){}}callback.done(result);});}
    static synchronized void stopped(){for(Session s:sessions.values())s.disconnect();publish();}
    static synchronized void rendered(String id,long received){Session s=sessions.get(id);if(s!=null){s.rendered++;if(received>0)s.maxRenderDelay=Math.max(s.maxRenderDelay,SystemClock.elapsedRealtime()-received);}}
    static synchronized JSONObject metrics()throws JSONException{JSONArray rows=new JSONArray();for(Session s:sessions.values())rows.put(new JSONObject().put("id",s.id).put("state",s.state).put("chunks",s.reads).put("characters",s.characters).put("renders",s.rendered).put("max_render_delay_ms",s.maxRenderDelay));return new JSONObject().put("sessions",rows);}
    private static synchronized void publish(){try{JSONArray tabs=new JSONArray();tabs.put(new JSONObject().put("id","console").put("name","系统").put("active",active.equals("console")).put("closable",false));for(Session s:sessions.values())tabs.put(new JSONObject().put("id",s.id).put("name",s.title).put("state",s.state).put("active",active.equals(s.id)).put("closable",true));VmService.terminalWindows=tabs.toString();VmService.signalOutput();}catch(JSONException ignored){}}
}
