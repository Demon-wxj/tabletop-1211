package local.tabletop.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import local.tabletop.TabletopMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

/** Public printed references only. No player, game-state, identity, or grimoire data is retained. */
public final class ReferenceScreen extends Screen {
    private record Section(String id,String title,String category,String alignment,String summary,List<String> paragraphs) {}
    private record Line(OrderedText text,int indent,int color) {}
    private static final int INK=0xff382b25,MUTED=0xff81715e,GOOD=0xff246879,EVIL=0xff8d3340;
    private static final int PAPER=0xffeee3c8,PAPER_LIGHT=0xfff5ecd9,EDGE=0xffbba27b;
    private static final int ROW_HEIGHT=44,DETAIL_ICON_SIZE=40;
    private String kind="",documentId="",documentTitle="桌游资料",selectedId="",query="",category="全部",alignment="";
    private List<Section> sections=List.of();
    private List<Line> detailLines=List.of();
    private final Map<String,ItemStack> icons=new HashMap<>();
    private TextFieldWidget searchField;
    private ButtonWidget categoryButton,alignmentButton,clearSearchButton,previousButton,nextButton,noResultsButton;
    private boolean compact,showDetail;
    private int left,top,panelWidth,panelHeight,innerLeft,innerRight;
    private int listLeft,listRight,listTop,listBottom,detailLeft,detailRight,detailTop,detailBottom;
    private int listScroll,detailScroll;

    public ReferenceScreen(JsonObject view){super(Text.literal("桌游资料"));accept(view);}
    static boolean supports(String kind){return Set.of("script_card","rules_card","rules_book","faq_book","flip_rules_book").contains(kind);}

    public void update(JsonObject view) {
        boolean changed=accept(view);
        if(changed&&client!=null){init(client,width,height);return;}
        if(categoryButton!=null)categoryButton.setMessage(Text.literal("分类："+category));
        wrapDetail();clampScroll();syncControls();
    }
    private boolean accept(JsonObject view) {
        String nextKind=str(view,"kind");JsonElement element=view.get("reference");
        JsonObject reference=element!=null&&element.isJsonObject()?element.getAsJsonObject():new JsonObject();
        String nextId=str(reference,"documentId");if(nextId.isBlank())nextId=nextKind;
        boolean changed=!documentId.equals(nextId)||!kind.equals(nextKind);
        kind=nextKind;documentId=nextId;documentTitle=str(reference,"title");
        if(documentTitle.isBlank())documentTitle=str(view,"title");
        if(documentTitle.isBlank())documentTitle="桌游资料";
        var content=new ArrayList<Section>();
        JsonElement entries=reference.get("sections");
        if(entries!=null&&entries.isJsonArray())for(JsonElement entry:entries.getAsJsonArray())if(entry.isJsonObject()) {
            JsonObject section=entry.getAsJsonObject();var paragraphs=new ArrayList<String>();
            JsonElement values=section.get("paragraphs");
            if(values!=null&&values.isJsonArray())for(JsonElement value:values.getAsJsonArray())if(value.isJsonPrimitive())paragraphs.add(value.getAsString());
            String id=str(section,"id");if(id.isBlank())id="section_"+content.size();
            content.add(new Section(id,str(section,"title"),str(section,"category"),str(section,"alignment"),str(section,"summary"),List.copyOf(paragraphs)));
        }
        if(content.isEmpty())content.add(new Section("unavailable","资料尚未加载","阅读说明","","",List.of("当前连接未提供阅读资料，请确认客户端和服务端安装同版本桌间 Mod。")));
        sections=List.copyOf(content);icons.clear();
        for(Section section:sections){
            String key="role_"+section.id;
            if(documentId.equals("flip_rules_book"))key=switch(section.id){case "flip_deck"->"number_12";case "flip_scoring"->"times_two";case "flip_freeze"->"freeze";case "flip_three","flip_chain"->"flip_three";case "flip_second_chance"->"second_chance";default->"flip_deck";};
            var item=TabletopMod.ITEMS.get(key);if(item!=null)icons.put(section.id,new ItemStack(item));
        }
        if(changed){query="";category="全部";alignment="";selectedId=sections.getFirst().id;showDetail=false;listScroll=0;detailScroll=0;}
        if(!categories().contains(category))category="全部";
        ensureSelection();return changed;
    }

    @Override protected void init() {
        panelWidth=Math.max(150,Math.min(width-18,780));panelHeight=Math.max(150,Math.min(height-18,560));
        left=(width-panelWidth)/2;top=(height-panelHeight)/2;innerLeft=left+14;innerRight=left+panelWidth-14;
        compact=panelWidth<520;clearChildren();searchField=null;categoryButton=null;alignmentButton=null;
        clearSearchButton=null;previousButton=null;nextButton=null;noResultsButton=null;
        button("×",innerRight-17,top+10,18,18,this::close);
        int contentTop;
        if(compact&&showDetail) {
            int controlWidth=Math.min(76,(innerRight-innerLeft-6)/2);
            button("返回目录",innerLeft,top+45,controlWidth,21,this::showContents);
            button("回到页首",innerLeft+controlWidth+6,top+45,controlWidth,21,()->detailScroll=0);
            contentTop=top+77;
        } else {
            searchField=new TextFieldWidget(textRenderer,innerLeft+6,top+47,innerRight-innerLeft-61,18,Text.literal(searchHint()));
            searchField.setMaxLength(100);searchField.setText(query);searchField.setEditableColor(INK);searchField.setUneditableColor(MUTED);
            searchField.setDrawsBackground(false);searchField.setPlaceholder(Text.literal(searchHint()));
            searchField.setChangedListener(value->{query=value;listScroll=0;detailScroll=0;ensureSelection();wrapDetail();syncControls();});addDrawableChild(searchField);
            clearSearchButton=button("清空",innerRight-43,top+45,43,21,this::clearSearch);
            int w=(innerRight-innerLeft-6)/2;
            categoryButton=button("分类："+category,innerLeft,top+71,w,20,this::cycleCategory);
            alignmentButton=button(hasAlignments()?alignmentLabel():"重置筛选",innerLeft+w+6,top+71,w,20,()->{if(hasAlignments())cycleAlignment();else resetFilters();});
            contentTop=top+102;
        }
        listLeft=innerLeft;listRight=compact?innerRight:innerLeft+Math.min(180,(innerRight-innerLeft)/3);
        listTop=contentTop;listBottom=top+panelHeight-52;
        detailLeft=compact?innerLeft:listRight+16;detailRight=innerRight;detailTop=contentTop;detailBottom=listBottom;
        if(!compact||showDetail){
            int navWidth=Math.min(90,(detailRight-detailLeft-12)/3),navY=detailBottom+6;
            previousButton=button(roleReference()?"上一角色":questionReference()?"上一问":"上一章",detailLeft,navY,navWidth,21,()->moveSection(-1));
            nextButton=button(roleReference()?"下一角色":questionReference()?"下一问":"下一章",detailRight-navWidth,navY,navWidth,21,()->moveSection(1));
        }
        if(!compact||!showDetail)noResultsButton=button("显示全部资料",listLeft+6,listTop+49,Math.min(150,listRight-listLeft-12),21,this::resetFilters);
        ensureSelection();wrapDetail();clampScroll();revealSelection();syncControls();
    }
    private ButtonWidget button(String title,int x,int y,int w,int h,Runnable action){return addDrawableChild(new PaperButton(title,x,y,w,h,action));}
    private List<String> categories(){var result=new LinkedHashSet<String>();result.add("全部");for(Section section:sections)if(!section.category.isBlank())result.add(section.category);return List.copyOf(result);}
    private String alignmentLabel(){return alignment.isBlank()?"全部阵营":alignment.equals("good")?"善良阵营":"邪恶阵营";}
    private boolean hasAlignments(){return sections.stream().anyMatch(section->!section.alignment.isBlank());}
    private boolean roleReference(){return documentId.equals("script_card");}
    private boolean questionReference(){return documentId.equals("faq_book");}
    private String searchHint(){return roleReference()?"搜索角色或能力":questionReference()?"搜索问题或解答":documentId.equals("flip_rules_book")?"搜索牌型、计分或流程":"搜索章节或规则";}
    private String unit(){return roleReference()?"角色":questionReference()?"问":"章";}
    private boolean hasFilters(){return !query.isBlank()||!category.equals("全部")||!alignment.isBlank();}
    private int selectedIndex(List<Section> values){for(int i=0;i<values.size();i++)if(values.get(i).id.equals(selectedId))return i;return -1;}
    private void clearSearch(){if(searchField!=null){searchField.setText("");setFocused(searchField);searchField.setFocused(true);}}
    private void resetFilters(){query="";category="全部";alignment="";if(searchField!=null)searchField.setText("");filterChanged();}
    private void syncControls(){
        List<Section> values=filtered();int index=selectedIndex(values);
        if(clearSearchButton!=null)clearSearchButton.active=!query.isEmpty();
        if(categoryButton!=null)categoryButton.setMessage(Text.literal("分类："+category));
        if(alignmentButton!=null){alignmentButton.active=hasAlignments()||hasFilters();alignmentButton.setMessage(Text.literal(hasAlignments()?alignmentLabel():"重置筛选"));}
        if(previousButton!=null)previousButton.active=index>0;
        if(nextButton!=null)nextButton.active=index>=0&&index<values.size()-1;
        if(noResultsButton!=null){noResultsButton.visible=values.isEmpty();noResultsButton.active=values.isEmpty()&&hasFilters();}
    }
    private void cycleCategory(){List<String> values=categories();category=values.get((values.indexOf(category)+1)%values.size());filterChanged();}
    private void cycleAlignment(){alignment=switch(alignment){case ""->"good";case "good"->"evil";default->"";};filterChanged();}
    private void filterChanged(){listScroll=0;detailScroll=0;ensureSelection();wrapDetail();syncControls();}
    private List<Section> filtered() {
        String needle=query.strip().toLowerCase(Locale.ROOT);
        return sections.stream().filter(section->category.equals("全部")||category.equals(section.category))
            .filter(section->alignment.isBlank()||alignment.equals(section.alignment))
            .filter(section->needle.isBlank()||(section.title+" "+section.category+" "+section.summary+" "+String.join(" ",section.paragraphs)).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    private void ensureSelection(){List<Section> values=filtered();if(values.stream().noneMatch(section->section.id.equals(selectedId)))selectedId=values.isEmpty()?"":values.getFirst().id;}
    private Section current(){return sections.stream().filter(section->section.id.equals(selectedId)).findFirst().orElse(null);}
    private void openSection(Section section){selectedId=section.id;detailScroll=0;showDetail=true;setFocused(null);if(searchField!=null)searchField.setFocused(false);if(compact&&client!=null)init(client,width,height);else{wrapDetail();syncControls();}revealSelection();}
    private void showContents(){showDetail=false;if(client!=null)init(client,width,height);revealSelection();}
    private void revealSelection(){int index=selectedIndex(filtered());if(index<0)return;int start=index*ROW_HEIGHT,end=start+ROW_HEIGHT;if(start<listScroll)listScroll=start;else if(end>listScroll+listBottom-listTop)listScroll=end-(listBottom-listTop);clampScroll();}
    private void moveSection(int direction){List<Section> values=filtered();int index=selectedIndex(values),next=index+direction;if(index>=0&&next>=0&&next<values.size())openSection(values.get(next));}

    private void wrapDetail() {
        if(textRenderer==null)return;Section section=current();var lines=new ArrayList<Line>();
        if(section!=null) {
            int w=Math.max(40,detailRight-detailLeft-12),color=tint(section);
            addLines(lines,section.title,DETAIL_ICON_SIZE+9,color,w,true);
            String metadata=section.category+(section.alignment.isBlank()?"":" · "+(section.alignment.equals("good")?"善良阵营":"邪恶阵营"));
            addLines(lines,metadata,DETAIL_ICON_SIZE+9,MUTED,w,false);
            while(lines.size()<4)blank(lines);
            if(!section.summary.isBlank()){addLines(lines,roleReference()?"角色能力":questionReference()?"回答要点":"本章要点",0,color,w,true);addLines(lines,section.summary,0,INK,w,false);blank(lines);}
            for(String paragraph:section.paragraphs){for(String line:paragraph.split("\n",-1))addLines(lines,line,0,INK,w,false);blank(lines);}
        }
        detailLines=List.copyOf(lines);detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
    }
    private void addLines(List<Line> lines,String value,int indent,int color,int width,boolean bold){Text text=Text.literal(value);if(bold)text=text.copy().styled(style->style.withBold(true));for(OrderedText line:textRenderer.wrapLines(text,Math.max(30,width-indent)))lines.add(new Line(line,indent,color));}
    private static void blank(List<Line> lines){lines.add(new Line(Text.empty().asOrderedText(),0,INK));}
    private int maxListScroll(){return Math.max(0,filtered().size()*ROW_HEIGHT-Math.max(1,listBottom-listTop));}
    private int maxDetailScroll(){return Math.max(0,detailLines.size()*12+10-Math.max(1,detailBottom-detailTop));}
    private void clampScroll(){listScroll=Math.clamp(listScroll,0,maxListScroll());detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());}
    private int tint(Section section){return switch(section.alignment){case "good"->GOOD;case "evil"->EVIL;default->0xff80522e;};}
    private String trim(String value,int width){return textRenderer.trimToWidth(value,Math.max(8,width));}

    @Override public boolean shouldPause(){return false;}
    @Override public void renderBackground(DrawContext context,int mx,int my,float delta){}
    @Override public void resize(MinecraftClient client,int width,int height){super.resize(client,width,height);}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==70&&Screen.hasControlDown()){
            if(compact&&showDetail)showContents();
            if(searchField!=null){setFocused(searchField);searchField.setFocused(true);}
            return true;
        }
        if(searchField!=null&&searchField.isFocused()) {
            if(key==257||key==335){Section section=current();if(section!=null)openSection(section);return true;}
            return super.keyPressed(key,scan,modifiers);
        }
        if(key==256&&compact&&showDetail){showContents();return true;}
        if((key==263||key==262)&&(!compact||showDetail)){moveSection(key==263?-1:1);return true;}
        if(key==268||key==269){if(compact&&!showDetail)listScroll=key==268?0:maxListScroll();else detailScroll=key==268?0:maxDetailScroll();return true;}
        if(key==266||key==267){int step=(key==266?-1:1)*Math.max(24,detailBottom-detailTop-12);if(compact&&!showDetail)listScroll=Math.clamp(listScroll+step,0,maxListScroll());else detailScroll=Math.clamp(detailScroll+step,0,maxDetailScroll());return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        int delta=(int)(vertical*28);
        if((!compact||!showDetail)&&x>=listLeft&&x<listRight&&y>=listTop&&y<listBottom){listScroll=Math.clamp(listScroll-delta,0,maxListScroll());return true;}
        if((!compact||showDetail)&&x>=detailLeft&&x<detailRight&&y>=detailTop&&y<detailBottom){detailScroll=Math.clamp(detailScroll-delta,0,maxDetailScroll());return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0&&clearSearchButton!=null&&clearSearchButton.visible&&clearSearchButton.active&&x>=clearSearchButton.getX()&&x<clearSearchButton.getX()+clearSearchButton.getWidth()&&y>=clearSearchButton.getY()&&y<clearSearchButton.getY()+clearSearchButton.getHeight()){clearSearch();return true;}
        if(super.mouseClicked(x,y,button))return true;
        if(button==0&&(!compact||!showDetail)&&x>=listLeft&&x<listRight&&y>=listTop&&y<listBottom) {
            int index=((int)y-listTop+listScroll)/ROW_HEIGHT;List<Section> values=filtered();if(index>=0&&index<values.size()){openSection(values.get(index));return true;}
        }
        return false;
    }
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        drawPaper(c);
        c.drawText(textRenderer,Text.literal(trim(documentTitle,panelWidth-62)),innerLeft,top+14,INK,false);
        String subtitle=roleReference()?"角色技能":questionReference()?"常见问题":documentId.equals("flip_rules_book")?"七连翻规则":documentId.equals("rules_card")?"染钟楼谜团 · 简洁规则卡":"染钟楼谜团 · 详细规则流程";
        c.drawText(textRenderer,Text.literal(trim(subtitle,panelWidth-45)),innerLeft,top+29,MUTED,false);
        if(searchField!=null){c.fill(innerLeft,top+44,innerRight-48,top+67,0xffd2bf99);c.fill(innerLeft+1,top+45,innerRight-49,top+66,PAPER_LIGHT);}
        if(!compact||!showDetail)renderContents(c,mx,my);
        if(!compact||showDetail)renderDetail(c);
        List<Section> values=filtered();int index=selectedIndex(values);
        if(previousButton!=null){String position=index<0?"无匹配内容":(index+1)+" / "+values.size();int center=(detailLeft+detailRight)/2;c.drawText(textRenderer,Text.literal(position),center-textRenderer.getWidth(position)/2,detailBottom+12,MUTED,false);}
        String count=hasFilters()?"匹配 "+values.size()+" / "+sections.size()+" "+unit():sections.size()+" "+unit();
        String footer=(!compact||showDetail)&&index>=0?(hasFilters()?"匹配 "+values.size()+" / "+sections.size()+" · ":"")+"第 "+(index+1)+" / "+values.size()+" "+unit():"目录 · "+count;
        footer+=compact&&showDetail?" · Esc 目录":" · Ctrl+F 搜索 · Esc 收起";
        c.drawText(textRenderer,Text.literal(trim(footer,innerRight-innerLeft)),innerLeft,top+panelHeight-17,MUTED,false);
        super.render(c,mx,my,delta);
    }
    private void drawPaper(DrawContext c) {
        boolean board=kind.equals("script_card");
        c.fill(left-4,top-3,left+panelWidth+5,top+panelHeight+5,board?0xff402d24:0xff45302f);
        c.fill(left-2,top-1,left+panelWidth+3,top+panelHeight+3,0xffb29b76);
        c.fill(left,top,left+panelWidth,top+panelHeight,PAPER);
        c.fill(left+5,top+5,left+panelWidth-5,top+panelHeight-5,PAPER_LIGHT);
        c.fill(left+7,top+7,left+panelWidth-7,top+8,EDGE);c.fill(left+7,top+panelHeight-8,left+panelWidth-7,top+panelHeight-7,EDGE);
        c.fill(left+7,top+7,left+8,top+panelHeight-7,EDGE);c.fill(left+panelWidth-8,top+7,left+panelWidth-7,top+panelHeight-7,EDGE);
        if(!board){for(int n=0;n<3;n++)c.fill(left+panelWidth+n,top+7+n,left+panelWidth+n+1,top+panelHeight-2+n,0xffc1ad87);c.fill(left+9,top+9,left+11,top+panelHeight-9,0xffd1be9a);}
        if(!compact)c.fill(listRight+7,listTop-3,listRight+8,listBottom,EDGE);
    }
    private void renderContents(DrawContext c,int mx,int my) {
        List<Section> values=filtered();listScroll=Math.clamp(listScroll,0,maxListScroll());
        c.enableScissor(listLeft,listTop,listRight,listBottom);
        if(values.isEmpty()){c.drawText(textRenderer,Text.literal("没有匹配的"+unit()),listLeft+6,listTop+9,MUTED,false);c.drawText(textRenderer,Text.literal("请清空搜索或重置筛选"),listLeft+6,listTop+26,MUTED,false);}
        for(int i=0;i<values.size();i++) {
            Section section=values.get(i);int y=listTop+i*ROW_HEIGHT-listScroll;if(y+ROW_HEIGHT-2<=listTop||y>=listBottom)continue;
            boolean selected=section.id.equals(selectedId),hover=mx>=listLeft&&mx<listRight&&my>=Math.max(y,listTop)&&my<Math.min(y+ROW_HEIGHT-2,listBottom);
            c.fill(listLeft,y,listRight-3,y+ROW_HEIGHT-2,selected?0xffdcc79f:hover?0xffe8d7b7:0xfff0e5cf);
            c.fill(listLeft,y,listLeft+3,y+ROW_HEIGHT-2,tint(section));drawIcon(c,section,listLeft+7,y+7,28);
            int x=listLeft+42,w=listRight-x-9;
            c.drawText(textRenderer,Text.literal(trim(section.title,w)),x,y+7,tint(section),false);
            String metadata=section.category+(section.alignment.isBlank()?"":section.alignment.equals("good")?" · 善良":" · 邪恶");
            c.drawText(textRenderer,Text.literal(trim(metadata,w)),x,y+23,MUTED,false);
        }
        c.disableScissor();scrollbar(c,listRight-2,listTop,listBottom,values.size()*ROW_HEIGHT,listScroll);
    }
    private void renderDetail(DrawContext c) {
        c.fill(detailLeft-2,detailTop-3,detailRight,detailBottom,PAPER_LIGHT);
        c.enableScissor(detailLeft,detailTop,detailRight,detailBottom);
        Section section=current();if(section!=null)drawIcon(c,section,detailLeft+4,detailTop+5-detailScroll,DETAIL_ICON_SIZE);
        else if(!compact){c.drawText(textRenderer,Text.literal("没有匹配的阅读内容"),detailLeft+8,detailTop+12,MUTED,false);c.drawText(textRenderer,Text.literal("调整左侧条件，或显示全部资料。"),detailLeft+8,detailTop+29,MUTED,false);}
        for(int i=0;i<detailLines.size();i++){Line line=detailLines.get(i);int y=detailTop+5+i*12-detailScroll;if(y+12>=detailTop&&y<detailBottom)c.drawText(textRenderer,line.text,detailLeft+5+line.indent,y,line.color,false);}
        c.disableScissor();scrollbar(c,detailRight-2,detailTop,detailBottom,detailLines.size()*12+10,detailScroll);
    }
    private void drawIcon(DrawContext c,Section section,int x,int y,int size) {
        ItemStack item=icons.get(section.id);
        if(roleReference()&&item!=null)c.drawTexture(Identifier.of("tabletop","textures/item/role_"+section.id+".png"),x,y,0,0,size,size,size,size);
        else if(item!=null){float scale=size/16f;c.getMatrices().push();c.getMatrices().translate(x,y,0);c.getMatrices().scale(scale,scale,1);c.drawItem(item,0,0);c.getMatrices().pop();}
        else {int color=tint(section);c.fill(x,y,x+size,y+size,color);c.fill(x+2,y+2,x+size-2,y+size-2,0xffe4d3b1);String initial=section.title.isBlank()?"?":section.title.substring(0,1);c.drawText(textRenderer,Text.literal(initial),x+(size-textRenderer.getWidth(initial))/2,y+(size-8)/2,color,false);}
    }
    private void scrollbar(DrawContext c,int x,int start,int end,int full,int scroll){int h=end-start;if(h<=0||full<=h)return;int size=Math.max(10,h*h/full),y=start+(h-size)*scroll/Math.max(1,full-h);c.fill(x,y,x+2,y+size,EDGE);}
    private static String str(JsonObject object,String key){try{return TabletopClient.str(object,key);}catch(RuntimeException ex){return "";}}
    @Override public void removed(){sections=List.of();detailLines=List.of();icons.clear();query="";selectedId="";super.removed();}
    private final class PaperButton extends ButtonWidget {
        PaperButton(String text,int x,int y,int w,int h,Runnable action){super(x,y,w,h,Text.literal(text),button->action.run(),DEFAULT_NARRATION_SUPPLIER);}
        @Override protected void renderWidget(DrawContext c,int mx,int my,float delta){int x=getX(),y=getY();boolean hover=active&&(isHovered()||isFocused());c.fill(x,y,x+width,y+height,hover?0xff9c7448:active?0xffbea27b:0xffd4c5ab);c.fill(x+1,y+1,x+width-1,y+height-1,hover?0xffe3d0ab:active?0xffeddfc3:0xffeee6d6);String value=trim(getMessage().getString(),width-8);c.drawText(textRenderer,Text.literal(value),x+(width-textRenderer.getWidth(value))/2,y+(height-8)/2,active?INK:MUTED,false);}
    }
}
