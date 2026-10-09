package local.tabletop.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;

/** A card's current legal recipients, with no phone, chat, or extra confirmation. */
public final class FlipTargetScreen extends Screen {
    private record Choice(String id,String label){}
    private JsonObject data;
    private List<Choice> choices=List.of();
    private final List<ButtonWidget> targets=new ArrayList<>();
    private String context="",notice="";
    private boolean waiting,paused;
    private int page,left,top,panelWidth,panelHeight,rows,perPage;
    public FlipTargetScreen(JsonObject data){super(Text.literal("选择目标"));accept(data);}
    private void accept(JsonObject next){String key=TabletopClient.str(next,"context");if(!key.equals(context)){waiting=false;notice="";}context=key;data=next;paused=next.has("paused")&&next.get("paused").getAsBoolean();var list=new ArrayList<Choice>();if(next.has("choices"))for(var e:next.getAsJsonArray("choices")){var c=e.getAsJsonObject();list.add(new Choice(TabletopClient.str(c,"uuid"),TabletopClient.str(c,"label")));}choices=List.copyOf(list);}
    public void update(JsonObject next){accept(next);if(client!=null)init(client,width,height);}
    public void feedback(JsonObject reply){if(!TabletopClient.str(reply,"action").equals("flip_target")||!TabletopClient.str(reply,"session").equals(TabletopClient.str(data,"session")))return;waiting=false;if(reply.has("ok")&&!reply.get("ok").getAsBoolean())notice=TabletopClient.str(reply,"message");for(var button:targets)button.active=!paused&&!waiting;}
    @Override protected void init(){
        panelWidth=Math.max(150,Math.min(400,width-16));rows=Math.max(1,Math.min(9,(height-100)/24));perPage=rows*2;page=Math.clamp(page,0,Math.max(0,(choices.size()-1)/perPage));
        panelHeight=82+Math.min(rows,(Math.min(perPage,choices.size()-page*perPage)+1)/2)*24;left=(width-panelWidth)/2;top=(height-panelHeight)/2;
        clearChildren();targets.clear();int cell=(panelWidth-28)/2;
        for(int i=0;i<Math.min(perPage,choices.size()-page*perPage);i++){
            Choice choice=choices.get(page*perPage+i);String label=textRenderer.trimToWidth(choice.label,cell-10);
            var button=ButtonWidget.builder(Text.literal(label),b->{if(waiting||paused)return;waiting=true;for(var target:targets)target.active=false;TabletopClient.sendControl("flip_target",choice.id,"",context);}).dimensions(left+10+(i%2)*(cell+8),top+32+(i/2)*24,cell,20).build();button.active=!paused&&!waiting;targets.add(addDrawableChild(button));
        }
        int bottom=top+panelHeight-25;addDrawableChild(ButtonWidget.builder(Text.literal("收起"),b->close()).dimensions(left+(panelWidth-64)/2,bottom,64,20).build());
        if(choices.size()>perPage){var prev=addDrawableChild(ButtonWidget.builder(Text.literal("←"),b->{page--;init();}).dimensions(left+10,bottom,40,20).build());prev.active=page>0;var next=addDrawableChild(ButtonWidget.builder(Text.literal("→"),b->{page++;init();}).dimensions(left+panelWidth-50,bottom,40,20).build());next.active=(page+1)*perPage<choices.size();}
    }
    @Override public boolean shouldPause(){return false;}
    @Override public void renderBackground(DrawContext c,int mx,int my,float delta){}
    @Override public void render(DrawContext c,int mx,int my,float delta){
        c.fill(left-2,top-2,left+panelWidth+2,top+panelHeight+2,0xffb19b62);c.fill(left,top,left+panelWidth,top+panelHeight,0xf01a262c);
        c.drawCenteredTextWithShadow(textRenderer,Text.literal(TabletopClient.str(data,"title")+" · 选择目标"),width/2,top+10,0xffedd28e);
        String status=paused?"等待重连":notice;c.drawText(textRenderer,Text.literal(textRenderer.trimToWidth(status,panelWidth-20)),left+10,top+panelHeight-39,0xffe5cba0,false);super.render(c,mx,my,delta);
    }
}
