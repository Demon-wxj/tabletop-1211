package local.tabletop.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import local.tabletop.TabletopNet;
import local.tabletop.game.ClockSeats;
import local.tabletop.game.RoleCatalog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A private Storyteller surface. Choices and outcomes remain server-authoritative. */
public final class GrimoireScreen extends Screen {
    private record Contact(String id, String key, String name, int seat, int color) {}
    private record Seat(String id, int number, String role, String shownRole, boolean dead,
                        boolean poisoned, boolean drunk, boolean redHerring, boolean used,
                        boolean safe, boolean ghostVote) {}
    private record Task(String role, String actor, String status, boolean current,
                        boolean impaired, List<String> targets) {}
    private record Action(String label, String action, String target, String text,
                          String group, String confirm, boolean enabled, String reason, String context) {}
    private enum Tab { TABLE, NIGHT, SETUP, LOG }
    private enum Modal { NONE, TARGET, ROLE, FAKE_ROLE, TEXT, STATE, CONFIRM }

    private static final int INK=0xfff2eadd, MUTED=0xffb1a5ae, GOLD=0xffdcc083, SECRET_MARK=0xffffadb4;
    private static final int FRAME=0xff28202f, PANEL=0xff19151f, CARD=0xff352b3d, ACCENT=0xffa44864;
    private JsonObject data=new JsonObject(), state=new JsonObject();
    private List<Contact> contacts=List.of();
    private List<Seat> seats=List.of();
    private List<Task> tasks=List.of();
    private List<Action> actions=List.of();
    private List<RoleCatalog.Role> roleChoices=List.of();
    private List<OrderedText> logLines=List.of();
    private final List<BookButton> tabs=new ArrayList<>(), actionButtons=new ArrayList<>();
    private BookButton closeButton, moreButton, statusButton, seatPager;
    private TextFieldWidget textField;
    private String session="", viewer="", selectedSeat="", notice="", keptText="", taskKey="", draftKey="";
    private String modalTarget="", modalText="", modalKey="", selectedRole="", roleType="townsfolk";
    private String waitingAction="";
    private long generation=Long.MIN_VALUE, modalGeneration, logPage=1, logPages=1, logCount;
    private int ticks, waitingSince, noticeTick, actionPage, seatPage, modalScroll, logScroll, nightScroll;
    private int left, top, panelWidth, panelHeight, bodyTop, bodyBottom, actionTop, buttonHeight, actionColumns, actionSlots;
    private int gridTop, tileHeight, selectedY, popupLeft, popupTop, popupWidth, popupHeight;
    private boolean compact, noticeError;
    private Tab tab=Tab.TABLE;
    private Modal modal=Modal.NONE;
    private Action modalAction;

    public GrimoireScreen(JsonObject data) {
        super(Text.literal(str(data,"kind").equals("clock_review")?"染钟楼谜团 · 复盘":"染钟楼谜团 · 魔典"));
        accept(data);
        if(review())tab=Tab.LOG;
    }

    public void feedback(JsonObject reply) {
        if(!session.equals(str(reply,"session"))||!waitingAction.equals(str(reply,"action")))return;
        waitingAction="";showNotice(str(reply,"message"),!bool(reply,"ok"));updateControls();
    }

    public void update(JsonObject next) {
        rememberText();
        boolean reset=accept(next);
        if(textRenderer==null)return;
        if(reset){init(client,width,height);return;}
        if(modal==Modal.STATE&&selectedSeat.isBlank()){
            closeModal();showNotice("目标已离席。",true);
        }else if(modal!=Modal.NONE&&modal!=Modal.STATE&&(!current(modalAction)||
                (needsStepGuard(modalAction)&&!modalKey.equals(taskKey)))) {
            closeModal();showNotice("步骤已更新，请重新选择。",true);
        }
        wrapLog();updateControls();
    }

    private boolean accept(JsonObject next) {
        String nextSession=str(next,"session"), nextViewer=str(next,"viewer");
        long nextGeneration=number(next,"generation",0);
        boolean reset=!session.equals(nextSession)||!viewer.equals(nextViewer)||generation!=nextGeneration||!str(data,"kind").equals(str(next,"kind"));
        if(reset)clearPrivateState();
        data=next;state=object(next,"state");session=nextSession;viewer=nextViewer;generation=nextGeneration;
        if(reset&&review())tab=Tab.LOG;
        long nextPage=Math.max(1,number(state,"logPage",1));if(nextPage!=logPage)logScroll=0;
        logPage=nextPage;logPages=Math.max(1,number(state,"logPages",1));logCount=Math.max(0,number(state,"logCount",0));
        var people=new ArrayList<Contact>();
        for(JsonElement e:array(data,"contacts"))if(e.isJsonObject()){
            JsonObject c=e.getAsJsonObject();int seat=(int)number(c,"seat",0);people.add(new Contact(str(c,"id"),str(c,"key"),str(c,"name"),seat,0xff000000|(int)number(c,"color",seat>0?ClockSeats.color(seat):GOLD)));
        }
        contacts=List.copyOf(people);
        JsonArray source=array(state,"grimoire");if(source.isEmpty())source=array(state,"reviewGrimoire");if(source.isEmpty())source=array(state,"seats");
        var places=new ArrayList<Seat>();
        for(JsonElement e:source)if(e.isJsonObject()){
            JsonObject s=e.getAsJsonObject();String id=str(s,"uuid");
            places.add(new Seat(id,(int)number(s,"seat",places.size()+1),str(s,"role"),str(s,"shownRole"),bool(s,"dead"),bool(s,"poisoned"),bool(s,"drunk"),bool(s,"redHerring"),bool(s,"used"),bool(s,"safe"),ghostVote(id)));
        }
        if(places.isEmpty())for(Contact c:contacts)if(!c.key.equals("storyteller")&&!c.id.equals(str(state,"storyteller")))
            places.add(new Seat(c.id,c.seat>0?c.seat:places.size()+1,"","",false,false,false,false,false,false,false));
        places.sort(Comparator.comparingInt(Seat::number));seats=List.copyOf(places);
        if(seatCount()<=8)seatPage=0;
        if(seats.stream().noneMatch(s->s.id.equals(selectedSeat)))selectedSeat="";
        var queue=new ArrayList<Task>();
        for(JsonElement e:array(state,"nightTasks"))if(e.isJsonObject()){
            JsonObject t=e.getAsJsonObject();var chosen=new ArrayList<String>();
            for(JsonElement target:array(t,"targets"))if(target.isJsonPrimitive())chosen.add(target.getAsString());
            queue.add(new Task(str(t,"role"),str(t,"actor"),str(t,"status"),bool(t,"current"),bool(t,"impaired"),List.copyOf(chosen)));
        }
        tasks=List.copyOf(queue);
        var specs=new ArrayList<Action>();
        for(JsonElement e:array(data,"buttons"))if(e.isJsonObject())addUnique(specs,readAction(e.getAsJsonObject()));
        for(JsonElement e:array(state,"decisionChoices"))if(e.isJsonObject())addUnique(specs,readAction(e.getAsJsonObject()));
        JsonObject workflow=object(state,"workflow"), primary=object(workflow,"primaryAction");
        if(!str(primary,"action").isBlank()){
            Action a=readAction(primary);specs.removeIf(existing->sameCommand(existing,a));
            specs.addFirst(new Action(a.label,a.action,a.target,a.text,"primary",a.confirm,a.enabled&&(!workflow.has("canAdvance")||bool(workflow,"canAdvance")),a.reason,a.context));
        }
        actions=List.copyOf(specs);
        var roles=new ArrayList<RoleCatalog.Role>();
        for(JsonElement e:array(data,"roleChoices"))if(e.isJsonObject()){
            RoleCatalog.Role role=RoleCatalog.get(str(e.getAsJsonObject(),"id"));if(role!=null&&!roles.contains(role))roles.add(role);
        }
        roleChoices=List.copyOf(roles);
        Task current=tasks.stream().filter(Task::current).findFirst().orElse(null);
        String nextTaskKey=str(state,"phase")+":"+number(state,"night",0)+":"+number(state,"day",0)+":"+
                str(object(state,"vote"),"nominee")+":"+(current==null?"":current.role+current.actor+current.status+current.targets);
        if(!taskKey.equals(nextTaskKey))nightScroll=0;
        taskKey=nextTaskKey;
        return reset;
    }

    @Override protected void init() {
        panelWidth=Math.max(120,Math.min(width-8,960));panelHeight=Math.max(100,Math.min(height-8,620));
        left=(width-panelWidth)/2;top=(height-panelHeight)/2;compact=panelHeight<240;
        bodyTop=top+(compact?31:57);bodyBottom=top+panelHeight-13;
        buttonHeight=compact?14:24;actionColumns=!compact&&panelWidth>=560?4:3;actionSlots=actionColumns*2;
        actionTop=bodyBottom-(buttonHeight*2+3);gridTop=bodyTop+(compact?15:22);
        tileHeight=compact?22:Math.min(48,Math.max(24,(actionTop-gridTop-35)/3));
        selectedY=gridTop+tileHeight*2+4;
        clearChildren();tabs.clear();actionButtons.clear();
        closeButton=button("×",left+panelWidth-25,top+3,19,compact?14:20,false,this::close);
        moreButton=button("更多",left+panelWidth-67,top+3,38,compact?14:20,false,()->{actionPage++;updateControls();});
        int tw=(panelWidth-17)/4, ty=top+(compact?16:31);
        for(int i=0;i<4;i++){
            Tab value=Tab.values()[i];tabs.add(button(new String[]{"圆桌","夜间","配牌","记录"}[i],left+7+i*(tw+1),ty,tw,compact?13:21,tab==value,()->{
                rememberText();closeModal();tab=value;actionPage=0;updateControls();
            }));
        }
        statusButton=button("状态",left+panelWidth-52,selectedY-1,44,compact?12:20,false,()->{
            modal=Modal.STATE;modalAction=null;modalScroll=0;updateControls();
        });
        seatPager=button("9–12 →",left+panelWidth-107,selectedY-1,51,compact?12:20,false,()->{seatPage=1-seatPage;updateControls();});
        int bw=(panelWidth-18-(actionColumns-1)*3)/actionColumns;
        for(int i=0;i<actionSlots;i++){
            int index=i;actionButtons.add(button("",left+9+(i%actionColumns)*(bw+3),actionTop+(i/actionColumns)*(buttonHeight+3),bw,buttonHeight,false,()->invoke(index)));
        }
        popupBounds();
        textField=new TextFieldWidget(textRenderer,popupLeft+10,popupTop+32,popupWidth-20,22,Text.literal("信息"));
        textField.setMaxLength(600);textField.setEditableColor(INK);textField.setPlaceholder(Text.literal("填写内容"));
        textField.setText(keptText);textField.setChangedListener(value->keptText=value);addDrawableChild(textField);
        wrapLog();updateControls();
    }

    private void updateControls() {
        if(actionButtons.isEmpty())return;
        boolean plain=modal==Modal.NONE, connected=ClientPlayNetworking.canSend(TabletopNet.Action.ID);
        List<Action> available=visibleActions();int pages=Math.max(1,(available.size()+actionSlots-1)/actionSlots);actionPage=Math.floorMod(actionPage,pages);
        closeButton.visible=plain;moreButton.visible=plain&&pages>1;moreButton.active=waitingAction.isBlank();
        moreButton.setMessage(Text.literal((actionPage+1)+"/"+pages+" →"));
        for(int i=0;i<tabs.size();i++){tabs.get(i).visible=plain;tabs.get(i).primary=tab==Tab.values()[i];}
        statusButton.visible=plain&&!selectedSeat.isBlank()&&(tab==Tab.TABLE||tab==Tab.SETUP)&&!review();
        statusButton.active=connected&&waitingAction.isBlank()&&!seatActions().isEmpty();
        seatPager.visible=plain&&seatCount()>8&&(tab==Tab.TABLE||tab==Tab.SETUP);seatPager.setMessage(Text.literal(seatPage==0?"9–12 →":"← 1–8"));
        for(int i=0;i<actionButtons.size();i++){
            BookButton button=actionButtons.get(i);int index=actionPage*actionSlots+i;
            button.visible=plain&&index<available.size();
            button.active=button.visible&&connected&&waitingAction.isBlank()&&available.get(index).enabled;
            if(index<available.size()){
                Action a=available.get(index);button.setMessage(Text.literal(shortLabel(a)));button.primary=a.group.equals("primary")||a.action.equals("decision");
                String hint=!connected?"连接未就绪":!waitingAction.isBlank()?"等待确认":!a.enabled?(!a.reason.isBlank()?a.reason:"当前不可用"):a.label;
                button.setTooltip(Tooltip.of(Text.literal(hint)));
            }
        }
        if(textField!=null){textField.visible=modal==Modal.TEXT;textField.active=textField.visible;}
    }

    private List<Action> visibleActions() {
        var result=new ArrayList<Action>();
        for(Action a:actions){
            if(a.action.equals("votetime")||a.action.equals("timer")||a.action.equals("mark")||a.group.equals("seat")||a.action.equals("note")||a.action.equals("clear_note")||a.action.equals("clear_role")||a.action.equals("swap"))continue;
            String group=a.group;
            if(group.isBlank())group=switch(a.action){case "assign","autoassign","storyteller"->"config";case "review","logpage","exportlog","reset","close"->"log";case "resolve","decision","cancel_imp"->"night";default->"flow";};
            boolean show=switch(tab){
                case SETUP->group.equals("setup")||group.equals("config")||(!bool(state,"started")&&group.equals("primary"));
                case LOG->group.equals("log")||group.equals("review")||List.of("review","logpage","exportlog","reset","close").contains(a.action)||a.action.equals("open")&&a.target.equals("phone");
                case NIGHT->group.equals("night")||(a.action.equals("decision")&&isNight())||a.action.equals("resolve")||a.action.equals("cancel_imp")||
                        (group.equals("primary")&&isNight())||(group.equals("flow")&&isNight());
                case TABLE->group.equals("control")||group.equals("flow")||group.equals("primary")||
                        (group.equals("advanced")&&List.of("finish","close").contains(a.action))||(a.action.equals("decision")&&!isNight());
            };
            if(show)addUnique(result,a);
        }
        return List.copyOf(result);
    }

    private List<Action> seatActions() {
        var result=new ArrayList<Action>();if(selectedSeat.isBlank())return result;
        for(JsonElement e:array(object(state,"seatActions"),selectedSeat))if(e.isJsonObject())addUnique(result,readAction(e.getAsJsonObject()));
        for(Action a:actions)if((a.group.equals("seat")||List.of("mark","note","clear_note","clear_role","swap").contains(a.action))&&
                (a.target.equals("$target")||a.target.equals(selectedSeat)||a.action.equals("swap")&&a.text.equals(selectedSeat)))addUnique(result,a);
        return List.copyOf(result);
    }
    private boolean current(Action action) {
        if(action==null||!action.enabled)return false;if(actions.contains(action))return true;
        for(Seat seat:seats)for(JsonElement e:array(object(state,"seatActions"),seat.id))if(e.isJsonObject()&&readAction(e.getAsJsonObject()).equals(action))return true;
        return false;
    }
    private void invoke(int index) {
        List<Action> visible=visibleActions();int offset=actionPage*actionSlots+index;if(offset<visible.size())begin(visible.get(offset));
    }
    private void begin(Action action) {
        if(!action.enabled||!waitingAction.isBlank())return;
        if(!ClientPlayNetworking.canSend(TabletopNet.Action.ID)){showNotice("连接未就绪。",true);return;}
        showNotice("",false);
        modalAction=action;modalGeneration=generation;modalKey=taskKey;modalScroll=0;selectedRole="";
        modalTarget=action.target.equals("$target")?selectedSeat:action.target;modalText=action.text.equals("$text")?"":action.text;
        if(action.action.equals("swap")){modalTarget="";modal=Modal.TARGET;}
        else if(action.target.equals("$target")&&modalTarget.isBlank())modal=Modal.TARGET;
        else continueInput();
        updateControls();
    }
    private void continueInput() {
        modalScroll=0;
        if(modalAction.text.equals("$text")){
            if(modalAction.action.equals("assign")){
                if(roleChoices.isEmpty()){closeModal();showNotice("当前不能配牌。",true);return;}
                Seat seat=selected();RoleCatalog.Role role=seat==null?null:RoleCatalog.get(seat.role);roleType=role==null?"townsfolk":role.type();modal=Modal.ROLE;
            } else if(modalAction.action.equals("resolve")||modalAction.action.equals("note")){
                String nextDraft=modalAction.action+":"+modalTarget+":"+modalAction.context+":"+modalKey;
                if(!draftKey.equals(nextDraft)){keptText=modalAction.action.equals("note")?seatNote(modalTarget):str(state,"informationDraft");draftKey=nextDraft;}
                modal=Modal.TEXT;textField.setMaxLength(modalAction.action.equals("note")?160:600);textField.setText(keptText);updateControls();textField.setFocused(true);setFocused(textField);
            } else {closeModal();showNotice("此操作需要更新选择面板。",true);}
        } else confirmOrSubmit();
    }
    private void confirmOrSubmit() {
        if(modalAction==null)return;
        if(!modalAction.confirm.isBlank()){modal=Modal.CONFIRM;modalScroll=0;setFocused(null);}else submit();
        updateControls();
    }
    private static boolean needsStepGuard(Action a){return a!=null&&(a.action.equals("resolve")||a.action.equals("decision")||a.action.equals("cancel_imp"));}
    private void submit() {
        Action action=modalAction;
        if(!waitingAction.isBlank())return;
        if(generation!=modalGeneration||!current(action)||(needsStepGuard(action)&&!modalKey.equals(taskKey))){closeModal();showNotice("步骤已更新，请重新选择。",true);return;}
        if(action.target.equals("$target")&&seats.stream().noneMatch(s->s.id.equals(modalTarget))){closeModal();showNotice("目标已离席。",true);return;}
        if(action.text.equals("$text")&&modalText.isBlank()){showNotice("请填写内容。",true);return;}
        TabletopClient.sendControl(action.action,clean(modalTarget),clean(modalText),action.context);waitingAction=action.action;waitingSince=ticks;
        showNotice("等待确认 · "+shortLabel(action),false);closeModal();
    }
    private void closeModal(){rememberText();modal=Modal.NONE;modalAction=null;modalTarget="";modalText="";modalScroll=0;setFocused(null);updateControls();}
    private void rememberText(){if(textField!=null&&modal==Modal.TEXT)keptText=textField.getText();}
    private void showNotice(String text,boolean error){notice=text;noticeError=error;noticeTick=ticks;}

    private void wrapLog() {
        if(textRenderer==null)return;
        var rows=new ArrayList<String>();
        for(JsonElement e:array(state,"flowLog"))if(e.isJsonObject()){
            JsonObject entry=e.getAsJsonObject();String actor=str(entry,"actor"),target=str(entry,"target");
            String people=(actor.isBlank()?"":name(actor))+(target.isBlank()?"":" → "+name(target));
            rows.add("#"+number(entry,"sequence",0)+" "+logPhase(entry)+(bool(entry,"secret")?" · 私密":"")+(people.isBlank()?"":" · "+people)+"\n"+namedText(str(entry,"text")));
        }
        if(rows.isEmpty())for(JsonElement e:array(state,"announcements"))if(e.isJsonPrimitive())rows.add(namedText(e.getAsString()));
        if(rows.isEmpty())rows.add("暂无记录。");
        logLines=wrapRows(rows,panelWidth-28);logScroll=Math.clamp(logScroll,0,maxLogScroll());
    }

    @Override public void tick() {
        if(++ticks%60==0&&ClientPlayNetworking.canSend(TabletopNet.Action.ID))TabletopClient.send("refresh","","");
        if(!waitingAction.isBlank()&&ticks-waitingSince>200){waitingAction="";showNotice("未收到结果，请核对后再操作。",true);updateControls();}
        if(!noticeError&&!notice.isBlank()&&ticks-noticeTick>160)notice="";
    }
    @Override public boolean shouldPause(){return false;}
    @Override public void renderBackground(DrawContext c,int mouseX,int mouseY,float delta){}
    @Override public void resize(MinecraftClient client,int width,int height){rememberText();modal=Modal.NONE;modalAction=null;super.resize(client,width,height);}
    @Override public void removed(){clearPrivateState();}
    private void clearPrivateState(){
        data=new JsonObject();state=new JsonObject();contacts=List.of();seats=List.of();tasks=List.of();actions=List.of();roleChoices=List.of();logLines=List.of();
        selectedSeat="";notice="";keptText="";draftKey="";waitingAction="";modal=Modal.NONE;modalAction=null;modalTarget="";modalText="";taskKey="";modalKey="";
        generation=Long.MIN_VALUE;session="";viewer="";actionPage=0;seatPage=0;modalScroll=0;logScroll=0;nightScroll=0;noticeError=false;tab=Tab.TABLE;
        if(textField!=null){textField.setText("");textField.visible=false;}
    }

    @Override public void render(DrawContext c,int mx,int my,float delta) {
        rounded(c,left-2,top-2,panelWidth+4,panelHeight+4,9,0xff0d0910);rounded(c,left,top,panelWidth,panelHeight,8,FRAME);
        small(c,(review()?"复盘":"魔典 · "+seatCount()+"+1")+"  "+phaseLabel(),left+9,top+(compact?5:10),panelWidth-82,GOLD);
        rounded(c,left+7,bodyTop-1,panelWidth-14,Math.max(1,actionTop-bodyTop-3),5,PANEL);
        switch(tab){case TABLE,SETUP->renderTable(c,mx,my);case NIGHT->renderNight(c);case LOG->renderLog(c);}
        small(c,notice,left+9,top+panelHeight-10,panelWidth-18,noticeError?0xfff1a391:0xffc0ddb4);
        if(modal!=Modal.NONE)renderModal(c,mx,my);
        super.render(c,mx,my,delta);
        if(modal==Modal.NONE&&(tab==Tab.TABLE||tab==Tab.SETUP))renderSeatTooltip(c,mx,my);
        renderModalTooltip(c,mx,my);
    }

    private void renderTable(DrawContext c,int mx,int my) {
        int storytellerSize=compact?10:14;String storyteller=str(state,"storyteller");
        if(!storyteller.isBlank())playerAvatar(c,storyteller,left+11,bodyTop+2,storytellerSize);
        small(c,gatheringLabel(),left+storytellerSize+17,bodyTop+(compact?3:5),panelWidth-storytellerSize-31,GOLD);
        int cellWidth=(panelWidth-23)/4;
        for(int i=0;i<8;i++){
            int number=i+1+seatPage*8,x=left+9+(i%4)*(cellWidth+2),y=gridTop+(i/4)*(tileHeight+2);if(number>seatCount())continue;
            Seat seat=seats.stream().filter(s->s.number==number).findFirst().orElse(null);
            boolean hover=inside(mx,my,x,y,cellWidth,tileHeight), chosen=seat!=null&&seat.id.equals(selectedSeat);
            rounded(c,x,y,cellWidth,tileHeight,4,chosen?0xff79624d:hover?0xff59414f:CARD);
            int color=seat==null?0xff000000|ClockSeats.color(number):seatColor(seat.id);c.fill(x+2,y+3,x+4,y+tileHeight-3,color);
            int avatarSize=compact?10:Math.min(18,tileHeight-16),headerX=x+avatarSize+10;
            if(seat!=null)playerAvatar(c,seat.id,x+5,y+(compact?2:4),avatarSize);
            small(c,number+" "+(seat==null?"空席":name(seat.id)),headerX,y+(compact?3:5),cellWidth-avatarSize-15,seat!=null&&seat.dead?0xffbca3a5:INK);
            if(seat!=null){
                int roleY=y+(compact?12:Math.min(25,tileHeight-11));
                String redLabel=seat.redHerring?(!compact&&cellWidth>=110?"红鲱鱼":"红"):"";
                int redWidth=redLabel.isBlank()?0:textRenderer.getWidth(redLabel)+6;
                small(c,roleName(seat.role),x+4,roleY,cellWidth-8-(redWidth==0?0:redWidth+3),GOLD);
                if(redWidth>0){
                    int bx=x+cellWidth-redWidth-3;rounded(c,bx,roleY-1,redWidth,10,2,0xff733d4d);
                    small(c,redLabel,bx+3,roleY,redWidth-6,SECRET_MARK);
                }
                if(!compact&&tileHeight>=44){
                    boolean fake=!seat.shownRole.isBlank()&&!seat.shownRole.equals(seat.role);
                    String status=fake?cardStatuses(seat):"";
                    int statusWidth=textRenderer.getWidth(status);
                    small(c,fake?"假 · "+roleName(seat.shownRole):shortFlags(seat),x+4,y+35,cellWidth-8-(statusWidth==0?0:statusWidth+4),MUTED);
                    if(statusWidth>0)small(c,status,x+cellWidth-4-statusWidth,y+35,statusWidth,SECRET_MARK);
                }
            }
        }
        Seat selected=selected();
        JsonObject vote=object(state,"vote");
        String summary=selected==null?"":selected.number+"号 · 真 "+roleName(selected.role)+(!selected.shownRole.isBlank()&&!selected.shownRole.equals(selected.role)?" / 假 "+roleName(selected.shownRole):selected.dead?" · 亡":"");
        if(selected==null&&vote.size()>0)summary="提名 · "+name(str(vote,"nominee"))+" / 轮到 "+name(str(vote,"nextVoter"));
        small(c,summary,left+10,selectedY,panelWidth-(seatCount()>8?123:68),GOLD);
        int y=selectedY+(compact?14:25), h=actionTop-y-6;
        if(h>10){
            var rows=new ArrayList<String>();
            if(selected!=null){rows.add(selected.number+"号 · "+name(selected.id)+" · 真实身份 "+roleName(selected.role));rows.add(flags(selected));if(!selected.shownRole.isBlank()&&!selected.shownRole.equals(selected.role))rows.add("假身份 · "+roleName(selected.shownRole));
                String note=seatNote(selected.id);if(!note.isBlank())rows.add("备注 · "+note);
                RoleCatalog.Role role=RoleCatalog.get(selected.role);if(role!=null)rows.add(role.ability());}
            String workflow=str(object(state,"workflow"),"summary");if(!workflow.isBlank())rows.add(workflow);
            if(vote.size()>0)rows.add("投票 · "+name(str(vote,"nominee"))+" / 轮到 "+name(str(vote,"nextVoter")));
            else if(!str(state,"onBlock").isBlank())rows.add("处决席 · "+name(str(state,"onBlock"))+" / "+number(state,"bestVotes",0)+" 票");
            if(tab==Tab.SETUP){JsonObject counts=object(state,"setupCounts");if(counts.size()>0)rows.add("镇 / 外 / 爪 / 魔  "+countText(array(counts,"actual"))+"（应配 "+countText(array(counts,"expected"))+"）");}
            renderLines(c,wrapRows(rows,panelWidth-30),left+13,y,panelWidth-26,h,0,INK);
        }
    }

    private void playerAvatar(DrawContext c,String id,int x,int y,int size){
        UUID uuid;try{uuid=UUID.fromString(id);}catch(IllegalArgumentException e){return;}
        PlayerListEntry entry=client==null||client.getNetworkHandler()==null?null:client.getNetworkHandler().getPlayerListEntry(uuid);
        PlayerSkinDrawer.draw(c,entry==null?DefaultSkinHelper.getSkinTextures(uuid):entry.getSkinTextures(),x,y,size);
    }
    private void renderSeatTooltip(DrawContext c,int mx,int my){
        int cellWidth=(panelWidth-23)/4;
        for(int i=0;i<8;i++)if(inside(mx,my,left+9+(i%4)*(cellWidth+2),gridTop+(i/4)*(tileHeight+2),cellWidth,tileHeight)){
            int number=i+1+seatPage*8;Seat seat=seats.stream().filter(s->s.number==number).findFirst().orElse(null);if(seat==null)return;
            String info=number+"号 · "+name(seat.id)+"\n真实身份 · "+roleName(seat.role);
            if(!seat.shownRole.isBlank()&&!seat.shownRole.equals(seat.role))info+="\n假身份 · "+roleName(seat.shownRole);
            c.drawTooltip(textRenderer,Text.literal(info+"\n"+flags(seat)+redHerringHint(seat)),mx,my);return;
        }
        if(selected()!=null&&inside(mx,my,left+9,selectedY,panelWidth-18,12)){
            Seat seat=selected();String info=seat.number+"号 · "+name(seat.id)+"\n真实身份 · "+roleName(seat.role);
            if(!seat.shownRole.isBlank()&&!seat.shownRole.equals(seat.role))info+="\n假身份 · "+roleName(seat.shownRole);
            c.drawTooltip(textRenderer,Text.literal(info+"\n"+flags(seat)+redHerringHint(seat)),mx,my);
        }
    }

    private void renderNight(DrawContext c) {
        Task current=tasks.stream().filter(Task::current).findFirst().orElse(null);int y=bodyTop+5;
        if(current!=null){rounded(c,left+10,bodyTop+2,panelWidth-20,compact?40:45,4,0xff4c3545);c.fill(left+10,bodyTop+5,left+12,bodyTop+(compact?39:44),GOLD);}
        String headline=current==null?str(object(state,"workflow"),"summary"):name(current.actor)+" · "+roleName(current.role);
        small(c,headline.isBlank()?"暂无夜间待办":headline,left+13,y,panelWidth-26,GOLD);
        if(current!=null){small(c,statusLabel(current.status)+(current.impaired?" · 醉毒，请裁定":""),left+13,y+12,panelWidth-26,INK);
            if(!current.targets.isEmpty())small(c,"选择 · "+String.join("、",current.targets.stream().map(this::name).toList()),left+13,y+24,panelWidth-26,MUTED);}
        int queueY=y+(current==null?15:39),h=actionTop-queueY-6;
        if(h>10){
            var rows=new ArrayList<String>();
            String draft=str(state,"informationDraft"),warning=str(state,"informationWarning");
            if(!warning.isBlank())rows.add("注意 · "+warning);
            if(!draft.isBlank())rows.add("待发 · "+draft);
            for(JsonElement e:array(object(state,"workflow"),"blockers"))if(e.isJsonPrimitive())rows.add(e.getAsString());
            for(Task t:tasks)rows.add((t.current?"▶ ":"")+roleName(t.role)+" · "+name(t.actor)+" · "+statusLabel(t.status));
            List<OrderedText> lines=wrapRows(rows,panelWidth-30);nightScroll=Math.clamp(nightScroll,0,Math.max(0,lines.size()*12-h));
            renderLines(c,lines,left+13,queueY,panelWidth-26,h,nightScroll,INK);
        }
    }
    private void renderLog(DrawContext c){small(c,"记录 "+logCount+" · "+logPage+" / "+logPages,left+13,bodyTop+5,panelWidth-26,GOLD);renderLines(c,logLines,left+13,bodyTop+20,panelWidth-26,actionTop-bodyTop-26,logScroll,INK);}

    private void popupBounds(){popupWidth=Math.min(panelWidth-8,410);popupHeight=Math.min(panelHeight-6,340);popupLeft=left+(panelWidth-popupWidth)/2;popupTop=top+(panelHeight-popupHeight)/2;}
    private int modalListTop(){return popupTop+(modal==Modal.ROLE?43:modal==Modal.STATE?37:25);}
    private int modalListHeight(){return popupTop+popupHeight-25-modalListTop();}
    private int modalRowHeight(){return compact?18:25;}
    private String modalTitle(){return switch(modal){case TARGET->modalAction.action.equals("swap")?"互换 · 选择另一席":"选择玩家";case ROLE->"真实角色";case FAKE_ROLE->"酒鬼 · 镇民假身份";case TEXT->modalAction.action.equals("note")?"私密备注":"私密信息";case STATE->selected()==null?"席位状态":selected().number+"号 · 状态";case CONFIRM->"确认 · "+shortLabel(modalAction);default->"";};}
    private List<RoleCatalog.Role> offeredRoles(){return roleChoices.stream().filter(r->r.type().equals(modal==Modal.FAKE_ROLE?"townsfolk":roleType)).toList();}
    private List<Seat> targets(){return modalAction!=null&&modalAction.action.equals("swap")?seats.stream().filter(s->!s.id.equals(modalAction.text)&&!s.role.isBlank()).toList():seats;}
    private int modalCount(){return switch(modal){case TARGET->targets().size();case ROLE,FAKE_ROLE->offeredRoles().size();case STATE->seatActions().size();default->0;};}
    private int maxModalScroll(){return Math.max(0,((modalCount()+1)/2)*modalRowHeight()-modalListHeight());}
    private List<OrderedText> confirmationLines(){
        var rows=new ArrayList<String>();
        if(modalAction.action.equals("swap"))rows.add(name(modalText)+" ↔ "+name(modalTarget));
        else if(!modalTarget.isBlank())rows.add("对象 · "+name(modalTarget));
        if(modalAction.action.equals("assign"))rows.add("真实 · "+roleName(selectedRole)+(selectedRole.equals("drunk")?"\n表面 · "+roleName(modalText.substring(6)):""));
        else if(!modalText.isBlank()&&!List.of("decision","mark","swap").contains(modalAction.action))rows.add(modalText);
        rows.add(namedText(modalAction.confirm));return wrapRows(rows,popupWidth-24);
    }
    private int maxConfirmationScroll(){return Math.max(0,confirmationLines().size()*12-(popupHeight-54));}
    private void renderModal(DrawContext c,int mx,int my) {
        popupBounds();c.fill(left,top,left+panelWidth,top+panelHeight,0xc009070d);rounded(c,popupLeft-1,popupTop-1,popupWidth+2,popupHeight+2,7,GOLD);rounded(c,popupLeft,popupTop,popupWidth,popupHeight,6,FRAME);
        small(c,modalTitle(),popupLeft+10,popupTop+8,popupWidth-20,GOLD);
        if(modal==Modal.STATE&&selected()!=null)small(c,flags(selected()),popupLeft+10,popupTop+23,popupWidth-20,MUTED);
        if(modal==Modal.ROLE){int w=(popupWidth-20)/4;String[] ids={"townsfolk","outsider","minion","demon"}, names={"镇民","外来者","爪牙","恶魔"};
            for(int i=0;i<4;i++)drawChoice(c,names[i],popupLeft+10+i*w,popupTop+23,w-2,16,roleType.equals(ids[i]),true);}
        if(modal==Modal.TEXT){
            String who=modalTarget.isBlank()?currentActorName():name(modalTarget);small(c,who,popupLeft+10,popupTop+21,popupWidth-20,MUTED);
            small(c,noticeError?notice:modalAction.action.equals("note")?"仅说书人可见":"仅发送给当前玩家",popupLeft+10,popupTop+60,popupWidth-20,noticeError?0xfff1a391:MUTED);
        } else if(modal==Modal.CONFIRM){
            modalScroll=Math.clamp(modalScroll,0,maxConfirmationScroll());
            renderLines(c,confirmationLines(),popupLeft+12,popupTop+26,popupWidth-24,popupHeight-54,modalScroll,INK);
        } else renderChoices(c,mx,my);
        int by=popupTop+popupHeight-22,bw=(popupWidth-26)/2;
        drawChoice(c,"返回",popupLeft+9,by,bw,16,false,true);
        if(modal==Modal.TEXT||modal==Modal.CONFIRM)drawChoice(c,modal==Modal.TEXT?"下一步":"确认",popupLeft+17+bw,by,bw,16,true,waitingAction.isBlank());
        else if(maxModalScroll()>0)small(c,"滚轮翻阅",popupLeft+17+bw,by+4,bw,MUTED);
    }
    private void renderChoices(DrawContext c,int mx,int my) {
        int y0=modalListTop(),h=modalListHeight(),cell=(popupWidth-22)/2,row=modalRowHeight();modalScroll=Math.clamp(modalScroll,0,maxModalScroll());
        c.enableScissor(popupLeft+7,y0,popupLeft+popupWidth-7,y0+h);
        for(int i=0;i<modalCount();i++){
            int x=popupLeft+9+(i%2)*(cell+4),y=y0+(i/2)*row-modalScroll;if(y+row-2<y0||y>y0+h)continue;
            String label;boolean active=true;
            if(modal==Modal.TARGET){Seat s=targets().get(i);label=s.number+"号 "+name(s.id);}
            else if(modal==Modal.ROLE||modal==Modal.FAKE_ROLE){RoleCatalog.Role role=offeredRoles().get(i);label=role.name();}
            else {Action a=seatActions().get(i);label=shortLabel(a);active=a.enabled&&waitingAction.isBlank();}
            drawChoice(c,label,x,y,cell,row-2,inside(mx,my,x,y,cell,row-2),active);
        }
        c.disableScissor();scrollbar(c,popupLeft+popupWidth-4,y0,h,((modalCount()+1)/2)*row,modalScroll);
    }
    private void renderModalTooltip(DrawContext c,int mx,int my){
        if(modal!=Modal.STATE||!inside(mx,my,popupLeft+9,modalListTop(),popupWidth-18,modalListHeight()))return;
        int cell=(popupWidth-22)/2,column=(mx-popupLeft-9)/(cell+4),row=(my-modalListTop()+modalScroll)/modalRowHeight(),index=row*2+column;
        if(column<0||column>1||index<0||index>=seatActions().size())return;
        Action action=seatActions().get(index);String hint=!action.enabled&&!action.reason.isBlank()?action.reason:action.label;
        c.drawTooltip(textRenderer,Text.literal(hint),mx,my);
    }
    private void drawChoice(DrawContext c,String label,int x,int y,int w,int h,boolean highlighted,boolean active){rounded(c,x,y,w,h,3,highlighted?ACCENT:CARD);small(c,label,x+5,y+(h-8)/2,w-10,active?INK:MUTED);}

    @Override public boolean mouseClicked(double x,double y,int button) {
        if(modal!=Modal.NONE){
            popupBounds();int by=popupTop+popupHeight-22,bw=(popupWidth-26)/2;
            if(button==0&&inside(x,y,popupLeft+9,by,bw,16)){closeModal();return true;}
            if(button==0&&inside(x,y,popupLeft+17+bw,by,bw,16)){
                if(modal==Modal.TEXT){modalText=textField.getText().trim();if(modalText.isBlank())showNotice("请填写内容。",true);else confirmOrSubmit();}
                else if(modal==Modal.CONFIRM)submit();return true;
            }
            if(modal==Modal.TEXT){textField.mouseClicked(x,y,button);return true;}
            if(modal==Modal.CONFIRM)return true;
            if(modal==Modal.ROLE&&button==0&&inside(x,y,popupLeft+10,popupTop+23,popupWidth-20,16)){
                int index=(int)(x-popupLeft-10)/((popupWidth-20)/4);if(index>=0&&index<4){roleType=new String[]{"townsfolk","outsider","minion","demon"}[index];modalScroll=0;}return true;
            }
            if(button==0&&inside(x,y,popupLeft+9,modalListTop(),popupWidth-18,modalListHeight())){
                int cell=(popupWidth-22)/2,column=(int)(x-popupLeft-9)/(cell+4),row=((int)y-modalListTop()+modalScroll)/modalRowHeight();
                int index=row*2+column;if(column>1||index<0||index>=modalCount())return true;
                if(!inside(x,y,popupLeft+9+column*(cell+4),modalListTop()+row*modalRowHeight()-modalScroll,cell,modalRowHeight()-2))return true;
                if(modal==Modal.TARGET){Seat seat=targets().get(index);if(!modalAction.action.equals("swap")){selectedSeat=seat.id;seatPage=seat.number>8?1:0;}modalTarget=seat.id;continueInput();}
                else if(modal==Modal.ROLE||modal==Modal.FAKE_ROLE){RoleCatalog.Role role=offeredRoles().get(index);
                    if(modal==Modal.ROLE){selectedRole=role.id();modalText=role.id();if(role.id().equals("drunk")){modal=Modal.FAKE_ROLE;modalScroll=0;}else confirmOrSubmit();}
                    else {modalText="drunk "+role.id();confirmOrSubmit();}
                } else if(modal==Modal.STATE)begin(seatActions().get(index));
                updateControls();
            }
            return true;
        }
        if((button==0||button==1)&&(tab==Tab.TABLE||tab==Tab.SETUP)){
            int cell=(panelWidth-23)/4;
            for(int i=0;i<8;i++)if(inside(x,y,left+9+(i%4)*(cell+2),gridTop+(i/4)*(tileHeight+2),cell,tileHeight)){
                int number=i+1+seatPage*8;Seat seat=seats.stream().filter(s->s.number==number).findFirst().orElse(null);
                if(seat!=null){selectedSeat=seat.id;actionPage=0;updateControls();if(button==1&&!seatActions().isEmpty()){modal=Modal.STATE;modalScroll=0;updateControls();}}return true;
            }
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(modal!=Modal.NONE){
            if(modal==Modal.CONFIRM)modalScroll=Math.clamp(modalScroll-(int)(vertical*24),0,maxConfirmationScroll());
            else if(modal!=Modal.TEXT)modalScroll=Math.clamp(modalScroll-(int)(vertical*24),0,maxModalScroll());return true;
        }
        if(y>=actionTop){actionPage+=vertical<0?1:-1;updateControls();return true;}
        if(tab==Tab.LOG){logScroll=Math.clamp(logScroll-(int)(vertical*24),0,maxLogScroll());return true;}
        if(tab==Tab.NIGHT){nightScroll=Math.max(0,nightScroll-(int)(vertical*24));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(modal!=Modal.NONE){if(key==256){closeModal();return true;}if(modal==Modal.TEXT)return super.keyPressed(key,scan,modifiers);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean charTyped(char character,int modifiers){if(modal!=Modal.NONE&&modal!=Modal.TEXT)return true;return super.charTyped(character,modifiers);}

    private Seat selected(){return seats.stream().filter(s->s.id.equals(selectedSeat)).findFirst().orElse(null);}
    private int seatCount(){return Math.min(12,Math.max(Math.max(8,(int)number(state,"seatCapacity",8)),seats.stream().mapToInt(Seat::number).max().orElse(0)));}
    private String gatheringLabel(){String label=str(state,"tableSizeLabel");return label.isBlank()?"玩家 "+seats.size()+" / "+seatCount()+" · 说书人 1/1":label;}
    private String seatNote(String id){for(JsonElement e:array(state,"notes"))if(e.isJsonObject()&&str(e.getAsJsonObject(),"uuid").equals(id))return str(e.getAsJsonObject(),"text");return "";}
    private boolean review(){return str(data,"kind").equals("clock_review");}
    private boolean isNight(){return str(state,"phase").equals("night")||str(state,"phase").equals("first_night");}
    private boolean ghostVote(String id){for(JsonElement e:array(state,"seats"))if(e.isJsonObject()&&str(e.getAsJsonObject(),"uuid").equals(id))return bool(e.getAsJsonObject(),"ghostVote");return false;}
    private String currentActorName(){Task current=tasks.stream().filter(Task::current).findFirst().orElse(null);return current==null?"当前玩家":name(current.actor);}
    private String name(String id){for(Contact c:contacts)if(c.id.equals(id)||c.key.equals(id))return c.name;for(Seat s:seats)if(s.id.equals(id))return s.number+"号";return id.isBlank()?"无":"本桌玩家";}
    private int seatColor(String id){for(Contact c:contacts)if(c.id.equals(id))return c.color;for(Seat s:seats)if(s.id.equals(id))return 0xff000000|ClockSeats.color(s.number);return GOLD;}
    private String namedText(String value){for(Contact c:contacts)if(!c.id.isBlank())value=value.replace(c.id,c.name);return value;}
    private String phaseLabel(){return switch(str(state,"phase")){case "first_night"->"首夜";case "night"->"第 "+number(state,"night",0)+" 夜";case "day"->"第 "+number(state,"day",0)+" 天";default->bool(state,"finished")?"已结算":"准备";};}
    private static String roleName(String id){RoleCatalog.Role r=RoleCatalog.get(id);return r==null?switch(id){case "minion_info"->"爪牙互认";case "demon_info"->"恶魔信息";default->"未配牌";}:r.name();}
    private static String statusLabel(String value){return switch(value){case "resolved"->"已确认";case "skipped"->"已跳过";case "awaiting_confirmation"->"等待确认";case "awaiting_player"->"等待选择";default->value;};}
    private static String shortFlags(Seat s){return (s.dead?"亡":"活")+(s.drunk?" · 醉":"")+(s.poisoned?" · 毒":"")+(s.safe?" · 护":"")+(s.redHerring?" · 红鲱鱼":"");}
    private static String cardStatuses(Seat s){return (s.drunk?"醉":"")+(s.poisoned?"毒":"")+(s.safe?"护":"")+(s.used?"用":"");}
    private static String redHerringHint(Seat s){return s.redHerring?"\n占卜师将此善良玩家视为恶魔":"";}
    private static String flags(Seat s){var values=new ArrayList<String>();values.add(s.dead?"死亡"+(s.ghostVote?" · 幽灵票可用":""):"存活");if(s.drunk)values.add("醉酒");if(s.poisoned)values.add("中毒");if(s.redHerring)values.add("红鲱鱼");if(s.safe)values.add("僧侣保护");if(s.used)values.add("能力已用");return String.join(" · ",values);}
    private static String shortLabel(Action a){
        if(a.action.equals("mark")){String[] parts=a.text.split(" ");boolean on=parts.length>1&&parts[1].equals("true");String flag=parts.length==0?"":parts[0];return switch(flag){case "dead"->on?"标记死亡":"恢复存活";case "poisoned"->on?"添加中毒":"清除中毒";case "drunk"->on?"添加醉酒":"清除醉酒";case "red_herring"->on?"标记红鲱鱼":"移除红鲱鱼";case "used"->on?"能力已用":"恢复能力";case "safe"->on?"添加保护":"清除保护";case "imp"->"纠错继位";default->a.label;};}
        return switch(a.action){case "assign"->"选角色";case "autoassign"->"自动配牌";case "storyteller"->"换说书人";case "phones"->"拿出手机";case "start"->"开始首夜";case "resolve"->a.text.equals("$text")?"填写信息":a.label;case "note"->"私密备注";case "clear_note"->"清除备注";case "clear_role"->"清空角色";case "swap"->"互换角色";case "closevote"->"确认计票";case "execute"->"执行处决";case "phase"->a.target.equals("day")?"天亮":"入夜";case "cancel_imp"->"取消刀人";case "finish"->a.target.equals("good")?"善良获胜":"邪恶获胜";case "reset"->"准备下一局";case "close"->"关闭本桌";case "exportlog"->"导出记录";case "logpage"->a.target.equals("previous")?"上一页记录":a.target.equals("next")?"下一页记录":a.label;default->a.label;};
    }
    private static String logPhase(JsonObject e){return switch(str(e,"phase")){case "first_night"->"首夜";case "night"->"第 "+number(e,"night",0)+" 夜";case "day"->"第 "+number(e,"day",0)+" 天";case "setup"->"准备";case "finished"->"结算";default->"流程";};}
    private static String countText(JsonArray rows){var values=new ArrayList<String>();for(JsonElement e:rows)if(e.isJsonPrimitive())values.add(e.getAsString());return String.join(" / ",values);}
    private int maxLogScroll(){return Math.max(0,logLines.size()*12-(actionTop-bodyTop-26));}
    private List<OrderedText> wrapRows(List<String> rows,int width){var lines=new ArrayList<OrderedText>();for(String row:rows){for(String line:row.split("\\n"))lines.addAll(textRenderer.wrapLines(Text.literal(line),Math.max(12,width)));lines.add(Text.empty().asOrderedText());}return List.copyOf(lines);}
    private void renderLines(DrawContext c,List<OrderedText> lines,int x,int y,int w,int h,int scroll,int color){if(h<=0)return;c.enableScissor(x,y,x+w,y+h);for(int i=0;i<lines.size();i++){int py=y+i*12-scroll;if(py>=y-12&&py<y+h)c.drawText(textRenderer,lines.get(i),x,py,color,false);}c.disableScissor();scrollbar(c,x+w-1,y,h,lines.size()*12,scroll);}
    private void small(DrawContext c,String value,int x,int y,int width,int color){if(width>0)c.drawText(textRenderer,Text.literal(textRenderer.trimToWidth(value,width)),x,y,color,false);}
    private static String clean(String value){return value.replaceAll("[\\p{Cntrl}§]","");}
    private static void addUnique(List<Action> actions,Action action){if(!actions.stream().anyMatch(a->sameCommand(a,action)))actions.add(action);}
    private static boolean sameCommand(Action a,Action b){return a.action.equals(b.action)&&a.target.equals(b.target)&&a.text.equals(b.text);}
    private static Action readAction(JsonObject b){return new Action(str(b,"label"),str(b,"action"),str(b,"target"),str(b,"text"),str(b,"group"),str(b,"confirm"),!b.has("enabled")||bool(b,"enabled"),str(b,"reason"),str(b,"context"));}
    private static String str(JsonObject object,String key){return TabletopClient.str(object,key);}
    private static long number(JsonObject object,String key,long fallback){try{return object.has(key)?object.get(key).getAsLong():fallback;}catch(RuntimeException e){return fallback;}}
    private static boolean bool(JsonObject object,String key){try{return object.has(key)&&object.get(key).getAsBoolean();}catch(RuntimeException e){return false;}}
    private static JsonArray array(JsonObject object,String key){return object.has(key)&&object.get(key).isJsonArray()?object.getAsJsonArray(key):new JsonArray();}
    private static JsonObject object(JsonObject object,String key){return object.has(key)&&object.get(key).isJsonObject()?object.getAsJsonObject(key):new JsonObject();}
    private static boolean inside(double x,double y,int l,int t,int w,int h){return x>=l&&x<l+w&&y>=t&&y<t+h;}
    private static void rounded(DrawContext c,int x,int y,int w,int h,int radius,int color){if(w<=0||h<=0)return;int r=Math.min(radius,Math.min(w,h)/2);c.fill(x+r,y,x+w-r,y+h,color);c.fill(x,y+r,x+w,y+h-r,color);for(int yy=0;yy<r;yy++){int inset=r-(int)Math.sqrt(r*r-(r-yy)*(r-yy));c.fill(x+inset,y+yy,x+w-inset,y+yy+1,color);c.fill(x+inset,y+h-yy-1,x+w-inset,y+h-yy,color);}}
    private static void scrollbar(DrawContext c,int x,int y,int h,int total,int scroll){if(h<=0||total<=h)return;int thumb=Math.max(7,h*h/total);int py=y+(h-thumb)*scroll/Math.max(1,total-h);c.fill(x,py,x+2,py+thumb,0xff8e6d80);}
    private BookButton button(String label,int x,int y,int w,int h,boolean primary,Runnable action){return addDrawableChild(new BookButton(label,x,y,w,h,primary,action));}
    private final class BookButton extends ButtonWidget {
        boolean primary;
        BookButton(String label,int x,int y,int w,int h,boolean primary,Runnable action){super(x,y,w,h,Text.literal(label),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.primary=primary;}
        @Override protected void renderWidget(DrawContext c,int mx,int my,float delta){rounded(c,getX(),getY(),width,height,4,!active?0xff302932:primary?ACCENT:isHovered()?0xff59414f:CARD);String value=textRenderer.trimToWidth(getMessage().getString(),Math.max(1,width-6));c.drawText(textRenderer,Text.literal(value),getX()+(width-textRenderer.getWidth(value))/2,getY()+(height-8)/2,active?INK:MUTED,false);}
    }
}
