package local.tabletop.client;

import com.google.gson.JsonObject;
import local.tabletop.GameRuleList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import java.util.*;

public final class GameRulesScreen extends Screen {
    private final String game;
    private final List<GameRuleList.Rule> rules;
    private final GameTheme theme;
    private List<GameRuleList.Rule> visible=List.of();
    private int section;
    private final Map<String,String> draft=new LinkedHashMap<>();
    private String ticket,notice="";
    private boolean editable,pending,good;
    private int page,left,right,rows,footer,top,rowHeight,ticks;
    GameRulesScreen(JsonObject data){super(Text.literal(data.get("title").getAsString()+" · 对局设置"));game=data.get("game").getAsString();rules=GameRuleList.LISTS.get(game);theme=GameTheme.of(game);receive(data);}
    boolean sameGame(JsonObject d){return game.equals(d.get("game").getAsString());}
    void receive(JsonObject data){
        ticket=data.get("ticket").getAsString();editable=data.get("editable").getAsBoolean();pending=false;
        notice=data.get("message").getAsString();good=data.get("saved").getAsBoolean();
        if(draft.isEmpty()||good||!editable||notice.contains("已刷新")||notice.isEmpty())for(var r:rules)draft.put(r.key(),data.getAsJsonObject("values").get(r.key()).getAsString());
        if(client!=null)clearAndInit();
    }
    private ButtonWidget button(String name,int x,int y,int w,int h,Runnable action,boolean active){
        var b=ButtonWidget.builder(Text.literal(name),v->action.run()).dimensions(x,y,w,h).build();b.active=active;return addDrawableChild(b);
    }
    private boolean writing(){return editable&&!pending;}
    private void shift(GameRuleList.Rule r,int amount){
        int n;try{n=Integer.parseInt(draft.get(r.key()));}catch(NumberFormatException e){n=r.initial();}
        n=Math.max(r.min(),Math.min(r.max(),n+amount*r.step()));if(!r.accepts(n))n=r.min();draft.put(r.key(),Integer.toString(n));clearAndInit();
    }
    @Override protected void init(){
        int panel=Math.min(414,width-16);left=(width-panel)/2;right=left+panel;
        top=83;rowHeight=26;footer=height-50;rows=Math.max(1,(footer-top-3)/rowHeight);visible=theme.group(game,section);page=Math.max(0,Math.min(page,pages()-1));
        int tabW=(panel-14)/theme.tabs().size();for(int t=0;t<theme.tabs().size();t++){final int sel=t;button(theme.tabs().get(t).name(),left+7+t*tabW,58,tabW-2,18,()->{section=sel;page=0;clearAndInit();},!pending&&t!=section);}
        for(int i=0;i<rows&&page*rows+i<visible.size();i++){
            var r=visible.get(page*rows+i);int y=top+i*rowHeight+3;
            if(r.unit().equals("开关")){
                button(draft.get(r.key()).equals("1")?"已开启":"已关闭",right-109,y,99,18,()->{draft.put(r.key(),draft.get(r.key()).equals("1")?"0":"1");clearAndInit();},writing());
            }else{
                button("−",right-109,y,20,18,()->shift(r,-1),writing());
                var box=new TextFieldWidget(textRenderer,right-85,y,49,18,Text.literal(r.title()));box.setMaxLength(5);box.setTextPredicate(v->v.matches("[0-9]{0,5}"));box.setText(draft.get(r.key()));box.setChangedListener(v->draft.put(r.key(),v));box.setEditable(writing());addDrawableChild(box);
                button("+",right-31,y,21,18,()->shift(r,1),writing());
            }
        }
        int b=footer+23;int mid=(left+right)/2;
        button("上一页",left+7,footer,48,17,()->turn(-1),(section>0||page>0)&&!pending);
        button("下一页",right-55,footer,48,17,()->turn(1),(section+1<theme.tabs().size()||page+1<pages())&&!pending);
        button("恢复默认",left+7,b,70,19,()->{for(var r:rules)draft.put(r.key(),""+r.initial());notice="已填入默认值；点击保存后生效。";good=false;clearAndInit();},writing());
        button("返回",mid-29,b,58,19,this::close,true);
        button(pending?"保存中…":"保存设置",right-80,b,73,19,this::submit,writing());
    }
    private int pages(){return Math.max(1,(visible.size()+rows-1)/rows);}
    private void turn(int dir){page+=dir;if(page<0){section--;visible=theme.group(game,section);page=pages()-1;}else if(page>=pages()){section++;page=0;}clearAndInit();}
    private void showRule(String key){for(int t=0;t<theme.tabs().size();t++){int index=theme.tabs().get(t).keys().indexOf(key);if(index>=0){section=t;page=index/rows;return;}}}
    private void submit(){
        var values=new LinkedHashMap<String,Integer>();var encoded=new StringJoiner(",");
        for(int i=0;i<rules.size();i++){var r=rules.get(i);int n;try{n=Integer.parseInt(draft.get(r.key()));}catch(NumberFormatException e){n=-1;}
            if(!r.accepts(n)){showRule(r.key());good=false;notice=r.title()+"：请输入 "+r.min()+"–"+r.max()+(r.step()==2?"（2 或 4）":"");clearAndInit();return;}
            values.put(r.key(),n);encoded.add(Integer.toString(n));
        }
        try{GameRuleList.validate(game,values);}catch(IllegalArgumentException e){good=false;notice=e.getMessage();return;}
        TabletopClient.send("rules_save",ticket,encoded.toString());pending=true;ticks=0;notice="正在保存…";good=false;clearAndInit();
    }
    @Override public void tick(){if(pending&&++ticks>160){pending=false;good=false;notice="尚未收到保存结果，请重新打开核对。";clearAndInit();}}
    @Override public boolean shouldPause(){return false;}
    // The panel paints its own backdrop. Vanilla's later backdrop pass would blur the labels we already drew.
    @Override public void renderBackground(DrawContext d,int mx,int my,float delta){}
    @Override public void render(DrawContext d,int mx,int my,float delta){
        d.fill(0,0,width,height,0xa9111520);d.fill(left,5,right,height-4,theme.bg());d.fill(left,5,right,7,theme.edge());d.fill(left+4,10,right-4,39,theme.panel());
        d.drawItem(theme.icon().getDefaultStack(),left+10,16);d.drawTextWithShadow(textRenderer,title,left+34,13,theme.light());
        d.drawText(textRenderer,textRenderer.trimToWidth(theme.tagline(),right-left-45),left+34,26,0xffdfd7ca,false);
        String sectionName=theme.tabs().get(section).name();
        String hint=sectionName.equals("凋零弓")?"只影响本局搭建；床和地图原建筑始终受保护。":game.equals("classic_pvp")&&section==0?"水和岩浆可在赛区流动，但不能流进出生保护区。":"直接输入或点 ±，然后保存。20 刻 = 1 秒。";
        String guide=!editable?"对局进行中，只能查看。":notice.isEmpty()?hint:notice;
        d.drawText(textRenderer,textRenderer.trimToWidth(guide,right-left-18),left+10,44,good?0xff90ecc3:notice.isEmpty()?0xffd2ccbe:0xffffd188,false);
        int tabW=(right-left-14)/theme.tabs().size();d.fill(left+7+section*tabW,76,left+5+(section+1)*tabW,78,theme.edge());
        for(int i=0;i<rows&&page*rows+i<visible.size();i++){
            var r=visible.get(page*rows+i);int y=top+i*rowHeight;
            d.fill(left+6,y,right-6,y+24,i%2==0?theme.row():theme.panel());
            d.drawText(textRenderer,r.title(),left+11,y+3,0xfff0f5f6,false);
            String limits=r.unit().equals("开关")?"默认："+(r.initial()==1?"开启":"关闭"):
                    r.key().equals("teams")?"可选 2 或 4 队":
                    r.key().equals("wallWidth")?"可选 3、5、7、9 格":"范围 "+r.min()+"–"+r.max()+" "+r.unit();
            d.drawText(textRenderer,limits,left+11,y+14,0xffc6c8c4,false);
        }
        d.drawCenteredTextWithShadow(textRenderer,sectionName+"  "+(page+1)+" / "+pages(),width/2,footer+4,theme.light());
        super.render(d,mx,my,delta);
    }
}
