package local.tabletop.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import local.tabletop.TabletopNet;
import local.tabletop.TabletopMod;
import local.tabletop.game.ClockSeats;
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
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.*;

/** An in-world phone's reading surface. Only recipient-authorized server data is displayed.
 * Only local drafts survive putting away the phone, within this connection and match. */
public final class PhoneScreen extends Screen {
    private enum Page { HOME, CONVERSATIONS, CONTACTS, CHAT, ASSISTANT, TARGETS, SEATS, GROUP, IDENTITY, SPY, CARDS, CARD_PLAYERS }
    private record Contact(String id, String key, String name) {}
    private record Group(String key, String name, List<String> members) {}
    private record Chat(long sequence, String key, String sender, String text, boolean outgoing) {}
    private record Row(String key, String title, String preview, long latest, boolean group) {}
    private record Bubble(Chat chat, List<OrderedText> lines, int width, int height) {}
    private record Sending(String text, long afterSequence, int tick) {}

    private static final int INK=0xffeaf1f4, MUTED=0xff91a7b3, TEAL=0xff53d1c8;
    private static final int SURFACE=0xff142632, INCOMING=0xff263d4b, OUTGOING=0xff174e53;
    private record Memory(String session,String viewer,long generation,Page page,String conversation,Map<String,String> drafts) {}
    private static Memory memory;
    public static void clearMemory(){memory=null;}
    private final Deque<Page> backPages=new ArrayDeque<>();
    private static final Set<String> FLIP_CARD_IDS=Set.of("number_0","number_1","number_2","number_3","number_4","number_5","number_6","number_7","number_8","number_9","number_10","number_11","number_12","plus_2","plus_4","plus_6","plus_8","plus_10","times_two","freeze","flip_three","second_chance");
    private JsonObject data;
    private String session="", viewer="", conversation="storyteller", contactFilter="";
    private long generation=Long.MIN_VALUE, latestSequence;
    private int assistantInfoStamp, assistantReadStamp;
    private final NavigableMap<Long,Chat> received=new TreeMap<>();
    private final Map<String,String> drafts=new HashMap<>();
    private final Map<String,Long> readThrough=new HashMap<>();
    private final Map<String,Sending> sending=new HashMap<>();
    private List<Contact> contacts=List.of();
    private List<Group> groups=List.of();
    private List<Bubble> bubbles=List.of();
    private List<OrderedText> assistantLines=List.of();
    private final List<AppButton> appButtons=new ArrayList<>();
    private Page page=Page.HOME;
    private TextFieldWidget composeField, searchField, assistantTextField;
    private ButtonWidget sendButton, selectionConfirm;
    private final List<ButtonWidget> voteButtons=new ArrayList<>();
    private final List<SeatButton> seatButtons=new ArrayList<>();
    private ButtonWidget seatPager;
    private int seatChoicePage;
    private final LinkedHashSet<String> selectedTargets=new LinkedHashSet<>();
    private JsonObject selectedAction;
    private JsonObject confirmAction;
    private String confirmTarget="",confirmText="";
    private final Map<String,ItemStack> cardIcons=new HashMap<>();
    private String keptAssistantText="", keptGroupTargets="", notice="";
    private String waitingAction="";
    private int waitingSince;
    private long lastFeedbackId=-1;
    private String revealedCard="";
    private int revealTick=-100,cardSelection;
    private long lastRevealSequence=-1;
    private boolean detailReveal;
    private String cardOwner="";
    private Set<String> groupsBeforeCreation=Set.of();
    private boolean creatingGroup;
    private int ticks, left, top, phoneWidth, phoneHeight, innerLeft, innerRight, contentTop, contentBottom;
    private int listTop, listBottom, chatTop, chatBottom, assistantTop, assistantBottom;
    private int listScroll, chatScroll, assistantScroll, homeScroll, bubbleHeight, voteDataTick;
    private int homeHeight,homeRowHeight;

    public PhoneScreen(JsonObject data) {
        super(Text.literal("桌间手机"));
        this.data=new JsonObject();
        accept(data);
        page=ownPending()?Page.ASSISTANT:Page.HOME;
        if(memory!=null&&memory.session.equals(session)&&memory.viewer.equals(viewer)&&memory.generation==generation){
            drafts.putAll(memory.drafts);conversation=memory.conversation;
            if(memory.page==Page.CHAT||memory.page==Page.CONVERSATIONS)page=memory.page;
        }else clearMemory();
        if(bool(data,"openCards")&&publicFlipCards()){page=Page.CARDS;cardOwner=viewer;}
        openRequestedPanel(data);
    }

    /** Root network receiver calls this without replacing widgets or interrupting Chinese input. */
    public void update(JsonObject next) {
        saveDrafts();
        String previousStep=confirmationStep(data);
        boolean atBottom=chatScroll>=Math.max(0,bubbleHeight-(chatBottom-chatTop))-3;
        boolean reset=accept(next);
        if(!previousStep.equals(confirmationStep(next))&&confirmAction!=null){confirmAction=null;confirmTarget="";confirmText="";notice="步骤已更新，请重新选择。";}
        if(reset&&client!=null){page=session.isBlank()||page==Page.ASSISTANT?Page.ASSISTANT:Page.HOME;init(client,width,height);openRequestedPanel(next);return;}
        if(textRenderer!=null) {
            rebuildText();
            if(page==Page.CHAT&&atBottom)chatScroll=maxChatScroll();
            updateAssistantButtons();
        }
        if(creatingGroup)for(Group group:groups)if(!groupsBeforeCreation.contains(group.key)) {
            creatingGroup=false;keptGroupTargets="";showChat(group.key);break;
        }
        updateSendButton();
        if(page==Page.TARGETS&&!authorized(selectedAction)){selectedAction=null;selectedTargets.clear();showAssistant();notice="步骤已更新，请重新选择。";updateAssistantButtons();}
        if(page==Page.SPY&&!state().has("spyGrimoire")){navigate(Page.IDENTITY);notice="魔典尚未确认。";}
        if(page==Page.SEATS&&!bool(state(),"canChooseSeat")){showAssistant();notice="本局号码已锁定。";}
        updateSeatButtons();
        openRequestedPanel(next);
    }

    /** Feedback never replaces the reading surface or clears a message draft. */
    public void feedback(JsonObject result) {
        String resultSession=str(result,"session");
        if(!session.isBlank()&&!resultSession.isBlank()&&!session.equals(resultSession))return;
        long feedbackId=number(result,"requestId",-1);if(feedbackId>=0&&feedbackId<=lastFeedbackId)return;if(feedbackId>=0)lastFeedbackId=feedbackId;
        String action=str(result,"action");
        if(action.equals(waitingAction))waitingAction="";
        notice=bool(result,"ok")?"":str(result,"message");
        if(notice.isBlank()&&!bool(result,"ok"))notice="操作未完成，请检查当前阶段。";
        if(!bool(result,"ok")&&action.equals("group"))creatingGroup=false;
        if(bool(result,"ok")&&action.equals("choose_seat")&&page==Page.SEATS)showAssistant();
        updateAssistantButtons();
        updateSeatButtons();
    }

    private boolean accept(JsonObject next) {
        String nextSession=str(next,"session"), nextViewer=str(next,"viewer");
        long nextGeneration=number(next,"generation",0);
        boolean reset=!session.equals(nextSession)||!viewer.equals(nextViewer)||generation!=nextGeneration;
        if(reset)clearPrivateState();
        JsonObject previousState=object(data,"state"),nextState=object(next,"state");
        if(!reset&&number(previousState,"round",-1)==number(nextState,"round",-2)){
            JsonArray previousCards=array(object(object(previousState,"players"),nextViewer),"cards"),nextCards=array(object(object(nextState,"players"),nextViewer),"cards");
            List<String> remaining=new ArrayList<>();for(JsonElement e:previousCards)if(e.isJsonPrimitive())remaining.add(e.getAsString());
            for(int i=0;i<nextCards.size();i++){JsonElement e=nextCards.get(i);if(e.isJsonPrimitive()&&!remaining.remove(e.getAsString())){revealedCard=e.getAsString();revealTick=ticks;if(cardOwner.isBlank()||cardOwner.equals(nextViewer))cardSelection=i;detailReveal=true;}}
        }
        JsonObject lastReveal=object(nextState,"lastReveal");long revealSequence=number(lastReveal,"sequence",-1);
        if(!reset&&revealSequence>lastRevealSequence&&str(lastReveal,"player").equals(nextViewer)&&number(lastReveal,"round",-1)==number(nextState,"round",-2)&&!str(lastReveal,"cardId").isBlank()){
            revealedCard=str(lastReveal,"cardId");revealTick=ticks;detailReveal=true;
        }
        if(revealSequence>=0)lastRevealSequence=revealSequence;
        session=nextSession;viewer=nextViewer;generation=nextGeneration;data=next;voteDataTick=ticks;
        if(!publicFlipCards()||!object(state(),"players").has(cardOwner))cardOwner=viewer;
        cardSelection=Math.clamp(cardSelection,0,Math.max(0,publicCards().size()-1));
        int infoStamp=1;
        for(JsonElement line:array("body"))if(line.isJsonPrimitive()&&line.getAsString().startsWith("[主持信息]"))infoStamp=31*infoStamp+line.getAsString().hashCode();
        assistantInfoStamp=infoStamp;
        if(page==Page.ASSISTANT||page==Page.TARGETS)assistantReadStamp=assistantInfoStamp;
        var people=new ArrayList<Contact>();
        for(JsonElement e:array("contacts"))if(e.isJsonObject()) {
            JsonObject c=e.getAsJsonObject();people.add(new Contact(str(c,"id"),str(c,"key"),str(c,"name")));
        }
        contacts=List.copyOf(people);
        selectedTargets.removeIf(id->contacts.stream().noneMatch(c->c.id.equals(id)));
        var rooms=new ArrayList<Group>();
        for(JsonElement e:array("groups"))if(e.isJsonObject()) {
            JsonObject g=e.getAsJsonObject();var members=new ArrayList<String>();
            if(g.has("members")&&g.get("members").isJsonArray())for(JsonElement m:g.getAsJsonArray("members"))if(m.isJsonPrimitive())members.add(m.getAsString());
            rooms.add(new Group(str(g,"key"),str(g,"name"),List.copyOf(members)));
        }
        groups=List.copyOf(rooms);
        for(JsonElement e:array("chats"))if(e.isJsonObject()) {
            JsonObject m=e.getAsJsonObject();long sequence=number(m,"sequence",-1);
            if(sequence<0)continue;
            Chat chat=new Chat(sequence,str(m,"key"),str(m,"sender"),str(m,"text"),bool(m,"outgoing"));
            received.put(sequence,chat);latestSequence=Math.max(latestSequence,sequence);
            Sending pending=sending.get(chat.key);
            if(chat.outgoing&&pending!=null&&sequence>pending.afterSequence&&chat.text.equals(pending.text)) {
                sending.remove(chat.key);
                if(drafts.getOrDefault(chat.key,"").equals(pending.text))drafts.put(chat.key,"");
                if(page==Page.CHAT&&conversation.equals(chat.key)&&composeField!=null&&composeField.getText().equals(pending.text))composeField.setText("");
                notice="";
            }
        }
        while(received.size()>240)received.pollFirstEntry();
        if(page==Page.CHAT)readThrough.put(conversation,latestIn(conversation));
        return reset;
    }

    public void showHome(){backPages.clear();navigate(Page.HOME,false);}
    public void showConversations(){navigate(Page.CONVERSATIONS);}
    public void showContacts(){navigate(Page.CONTACTS);}
    public void showAssistant(){assistantReadStamp=assistantInfoStamp;if(page==Page.TARGETS||page==Page.SEATS){navigate(Page.ASSISTANT,false);if(backPages.peekFirst()==Page.ASSISTANT)backPages.removeFirst();}else navigate(Page.ASSISTANT);}
    public void showIdentity(){navigate(Page.IDENTITY);}
    private void showSpy(){if(state().has("spyGrimoire"))navigate(Page.SPY);}
    /** Open only the nomination action supplied by the server for this viewer. */
    public void showNomination(){
        for(JsonElement value:array("buttons"))if(value.isJsonObject()){JsonObject action=value.getAsJsonObject();if(str(action,"action").equals("nominate")&&str(action,"target").equals("$target")){
            if(action.has("enabled")&&!bool(action,"enabled")){showAssistant();notice=str(action,"reason");updateAssistantButtons();return;}
            selectedAction=action.deepCopy();selectedTargets.clear();notice="";navigate(Page.TARGETS);return;
        }}
        showAssistant();
    }
    public void showVoting(){showAssistant();}
    public void showSeats(){
        if(!bool(state(),"canChooseSeat")){showAssistant();return;}
        seatChoicePage=number(state(),"ownSeat",0)>8?1:0;navigate(Page.SEATS);
    }
    private void openRequestedPanel(JsonObject view){
        if(bool(view,"openSpy")&&object(object(view,"state"),"spyGrimoire").size()>0){if(page!=Page.SPY)showSpy();}
        else if(bool(view,"openSeats")){if(page!=Page.SEATS)showSeats();}
        else if(bool(view,"openNomination"))showNomination();
        else if(bool(view,"openVoting"))showVoting();
    }
    public void showCards(){if(publicFlipCards()){if(!cardOwner.equals(viewer))cardSelection=0;cardOwner=viewer;navigate(Page.CARDS);}}
    public void showCardPlayers(){if(publicFlipCards())navigate(Page.CARD_PLAYERS);}
    private void showPublicCards(String owner){if(publicFlipCards()&&object(state(),"players").has(owner)){cardOwner=owner;cardSelection=0;detailReveal=false;navigate(Page.CARDS);}}
    public void showGroup(){navigate(Page.GROUP);}
    public void showChat(String key) {
        if(key==null||key.isBlank())return;
        saveDrafts();rememberPage(Page.CHAT);conversation=key;notice="";page=Page.CHAT;listScroll=0;
        readThrough.put(key,latestIn(key));
        if(client!=null){init(client,width,height);chatScroll=maxChatScroll();}
    }
    private void navigate(Page target) {
        navigate(target,true);
    }
    private void navigate(Page target,boolean remember) {
        saveDrafts();if(remember)rememberPage(target);page=target;notice="";listScroll=0;assistantScroll=0;
        if(client!=null)init(client,width,height);
    }
    private void rememberPage(Page target){if(page!=target){backPages.addFirst(page);while(backPages.size()>10)backPages.removeLast();}}
    private void back() {
        if(page==Page.HOME)return;
        Page previous=backPages.pollFirst();if(previous==null)previous=page==Page.CHAT?Page.CONVERSATIONS:Page.HOME;
        if(previous==Page.TARGETS)previous=Page.ASSISTANT;
        if(previous==Page.SEATS&&!bool(state(),"canChooseSeat"))previous=Page.ASSISTANT;
        navigate(previous,false);
    }
    private boolean compact(){return phoneHeight<220;}
    private int voteHeaderHeight(){return compact()?51:70;}
    private int rowHeight(){return compact()?35:51;}
    private int targetRowHeight(){return compact()?26:43;}
    private int actionRowHeight(){return compact()?25:36;}
    private int homeIntroHeight(){return compact()?0:18;}

    @Override protected void init() {
        phoneHeight=Math.max(120,Math.min(height-12,482));
        phoneWidth=Math.max(100,Math.min(width-12,Math.min(250,Math.max(170,(int)(phoneHeight*.60)))));
        left=(width-phoneWidth)/2;top=(height-phoneHeight)/2;
        innerLeft=left+13;innerRight=left+phoneWidth-13;contentTop=top+(compact()?35:61);contentBottom=top+phoneHeight-(compact()?24:34);
        listTop=contentTop;listBottom=contentBottom;chatTop=contentTop+3;chatBottom=contentBottom;
        clearChildren();appButtons.clear();voteButtons.clear();seatButtons.clear();seatPager=null;
        composeField=null;searchField=null;assistantTextField=null;sendButton=null;selectionConfirm=null;
        int navWidth=(innerRight-innerLeft-6)/2;
        int navY=top+phoneHeight-(compact()?20:28),navHeight=compact()?16:20;
        ButtonWidget backButton=smallButton("返回",innerLeft,navY,navWidth,navHeight,this::back);backButton.active=page!=Page.HOME;
        ButtonWidget homeButton=smallButton("首页",innerLeft+navWidth+6,navY,navWidth,navHeight,this::showHome);homeButton.active=page!=Page.HOME;
        smallButton("收起",innerRight-31,top+(compact()?12:34),31,compact()?16:18,this::close);
        int w=innerRight-innerLeft;
        switch(page) {
            case HOME -> initHome();
            case CONVERSATIONS -> {
                smallButton("通讯录",innerLeft,contentTop+2,(w-5)/2,19,this::showContacts);
                smallButton("建群",innerLeft+(w+5)/2,contentTop+2,(w-5)/2,19,this::showGroup);
                listTop=contentTop+28;
            }
            case CONTACTS -> {
                searchField=field(innerLeft,contentTop+2,w-49,18,"搜索联系人",contactFilter,80);
                searchField.setChangedListener(value->{contactFilter=value;listScroll=0;});
                smallButton("建群",innerRight-44,contentTop+2,44,18,this::showGroup);listTop=contentTop+28;
            }
            case CHAT -> initChat();
            case GROUP -> {
                smallButton("创建小组",innerLeft,contentBottom-(compact()?19:21),w,compact()?18:19,this::createGroup);
                listTop=contentTop+(compact()?12:20);listBottom=contentBottom-(compact()?22:48);
            }
            case ASSISTANT -> initAssistant();
            case TARGETS -> initTargetSelection();
            case SEATS -> initSeatSelection();
            case IDENTITY -> {if(state().has("spyGrimoire")){smallButton("已确认魔典",innerLeft,contentBottom-21,w,19,this::showSpy);listBottom=contentBottom-26;}}
            case SPY -> {}
            case CARDS -> {
                listTop=contentTop+cardPreviewHeight()+54;listBottom=Math.max(listTop+1,contentBottom-29);
                smallButton("查看玩家",innerRight-55,contentTop+2,55,21,this::showCardPlayers);
                smallButton("上一张",innerLeft,contentBottom-22,(w-4)/2,19,()->cycleCard(-1));
                smallButton("下一张",innerLeft+(w+4)/2,contentBottom-22,(w-4)/2,19,()->cycleCard(1));
            }
            case CARD_PLAYERS -> listTop=contentTop+18;
        }
        rebuildText();updateSendButton();updateSeatButtons();
    }

    private void initHome() {
        String[] labels={"游戏","消息","联系人",flipGame()?"桌前牌":"身份"};
        String[] sub={session.isBlank()?"创建 · 加入":"当前行动","公聊 · 私聊","本桌玩家",flipGame()?"公开牌":"身份与技能"};
        int[] colors={0xff825395,0xff237e84,0xff3771a4,0xff966642};int[] icons={2,0,1,3};
        Runnable[] actions={this::showAssistant,this::showConversations,this::showContacts,flipGame()?this::showCards:this::showIdentity};
        int gap=compact()?4:8, cell=(innerRight-innerLeft-gap)/2;
        homeRowHeight=compact()?Math.max(26,(contentBottom-contentTop-4)/2):Math.max(54,Math.min(76,(contentBottom-contentTop-homeIntroHeight())/2));
        homeHeight=homeIntroHeight()+2*homeRowHeight;
        homeScroll=Math.min(homeScroll,Math.max(0,homeHeight-(contentBottom-contentTop)));
        for(int i=0;i<labels.length;i++) {
            AppButton button=new AppButton(labels[i],sub[i],icons[i],colors[i],actions[i],innerLeft+(i%2)*(cell+gap),contentTop+homeIntroHeight()+(i/2)*homeRowHeight-homeScroll,cell,homeRowHeight-(compact()?3:6));
            if(i==3&&flipGame())button.active=publicFlipCards();
            appButtons.add(button);addDrawableChild(button);
        }
    }

    private void initChat() {
        int w=innerRight-innerLeft,h=compact()?20:26,composeY=contentBottom-h;
        composeField=field(innerLeft,composeY,w-43,h,"发送消息",drafts.getOrDefault(conversation,""),600);
        composeField.setChangedListener(value->{drafts.put(conversation,value);updateSendButton();});
        sendButton=addDrawableChild(new PhoneButton("发送",innerRight-37,composeY,37,h,true,this::sendMessage));
        chatBottom=Math.max(chatTop,composeY-(compact()?4:15));
    }

    private void initAssistant() {
        int w=innerRight-innerLeft,bw=(w-8)/3;
        for(int slot=0;slot<3;slot++) {
            final int index=slot;
            ButtonWidget button=smallButton("",innerLeft+slot*(bw+4),contentTop+(compact()?30:43),bw,compact()?18:22,()->voteAction(index));
            voteButtons.add(button);
        }
        updateAssistantButtons();
    }

    private void updateAssistantButtons() {
        JsonObject vote=voteState();boolean voting=!vote.entrySet().isEmpty();
        assistantTop=contentTop+(voting?voteHeaderHeight()+4:flipGame()?flipHeaderHeight()+5:taskCardHeight()>0?taskCardHeight()+4:2);assistantBottom=contentBottom-(!notice.isBlank()||!waitingAction.isBlank()?(compact()?12:25):0);
        for(int slot=0;slot<voteButtons.size();slot++) {
            ButtonWidget button=voteButtons.get(slot);JsonObject spec=voteSpec(slot);
            button.visible=voting&&spec!=null;
            button.active=button.visible&&waitingAction.isBlank()&&(!spec.has("enabled")||bool(spec,"enabled"))&&!bool(state(),"tablePaused")&&(!vote.has("mayVote")||bool(vote,"mayVote"));
            if(spec!=null&&!str(spec,"reason").isBlank())button.setTooltip(Tooltip.of(Text.literal(str(spec,"reason"))));
            button.setMessage(Text.literal(slot==0?"支持":slot==1?"不支持":"取消支持"));
        }
        if(selectionConfirm!=null)selectionConfirm.active=canConfirmSelection();
        assistantScroll=Math.clamp(assistantScroll,0,maxAssistantScroll());
    }

    private JsonObject state(){JsonElement value=data.get("state");return value!=null&&value.isJsonObject()?value.getAsJsonObject():new JsonObject();}
    private JsonObject voteState(){JsonElement value=state().get("vote");return value!=null&&value.isJsonObject()?value.getAsJsonObject():new JsonObject();}
    private JsonObject voteSpec(int slot) {
        String action=slot==2?"unvote":"vote",target=slot==0?"yes":"no";
        for(JsonElement e:array("buttons"))if(e.isJsonObject()) {
            JsonObject spec=e.getAsJsonObject();
            if(str(spec,"action").equals(action)&&(slot==2||str(spec,"target").equals(target)))return spec;
        }
        return null;
    }
    private void voteAction(int slot) {
        JsonObject spec=voteSpec(slot);if(spec==null||voteState().entrySet().isEmpty())return;
        if(voteState().has("mayVote")&&!bool(voteState(),"mayVote"))return;
        executeAction(spec,str(spec,"target"),str(spec,"text"));
    }
    private List<JsonObject> assistantActions() {
        var actions=new ArrayList<JsonObject>();
        for(JsonElement e:array("buttons"))if(e.isJsonObject()) {
            JsonObject spec=e.getAsJsonObject();String action=str(spec,"action");
            boolean primary=Set.of("create","join","choose_seat","draw","stay","start","next","reset","target","submit","nominate","slay","leave","close").contains(action)||action.equals("open")&&str(spec,"target").equals("grimoire");
            if(!action.equals("vote")&&!action.equals("unvote")&&primary)actions.add(spec);
        }
        // Keep the active night reply and the public nomination visible before reference items.
        actions.sort(Comparator.comparingInt(spec->switch(str(spec,"action")){case "create","join","draw","stay","start","next","reset","target"->0;case "open"->str(spec,"target").equals("grimoire")?0:3;case "submit"->state().has("pendingTask")?0:3;case "nominate"->str(state(),"phase").equals("day")?0:1;default->3;}));
        return actions;
    }
    private void assistantAction(JsonObject spec) {
        if(spec.has("enabled")&&!bool(spec,"enabled")){notice=str(spec,"reason").isBlank()?"当前条件尚未满足。":str(spec,"reason");updateAssistantButtons();return;}
        if(!waitingAction.isBlank())return;
        if(str(spec,"action").equals("choose_seat")){showSeats();return;}
        if(str(spec,"action").equals("group")){showGroup();return;}
        if(str(spec,"target").equals("$target")||str(spec,"text").equals("$text")) {
            selectedAction=spec.deepCopy();selectedTargets.clear();notice="";navigate(Page.TARGETS);return;
        }
        executeAction(spec,str(spec,"target"),str(spec,"text"));
    }
    private boolean authorized(JsonObject spec) {
        if(spec==null)return false;
        for(JsonElement e:array("buttons"))if(e.isJsonObject()&&e.getAsJsonObject().equals(spec))return true;
        return false;
    }
    private void executeAction(JsonObject spec,String target,String text) {
        if(!authorized(spec)){notice="当前阶段已变化，请返回重新选择。";return;}
        if(!str(spec,"confirm").isBlank()){confirmAction=spec.deepCopy();confirmTarget=target;confirmText=text;setFocused(null);return;}
        sendAuthorized(spec,target,text);
    }
    private void sendAuthorized(JsonObject spec,String target,String text){
        if(!authorized(spec)||spec.has("enabled")&&!bool(spec,"enabled")){notice="当前阶段已变化，请重新选择。";return;}
        String action=str(spec,"action");if(!action.equals("open")&&!action.equals("refresh")){waitingAction=action;waitingSince=ticks;notice="";}
        TabletopClient.sendControl(action,target,text,str(spec,"context"));updateAssistantButtons();
    }
    private int targetCount() {
        if(selectedAction==null||!str(selectedAction,"target").equals("$target"))return 0;
        if(str(selectedAction,"action").equals("submit")) {
            JsonElement pending=state().get("pendingTask");
            if(pending!=null&&pending.isJsonObject())return (int)Math.clamp(number(pending.getAsJsonObject(),"targetCount",1),0,2);
        }
        return 1;
    }
    private List<Contact> targetContacts(){
        boolean flip=state().has("players");JsonObject pending=object(state(),"pendingAction");
        Set<String> allowed=new HashSet<>();for(JsonElement e:array(pending,"targets"))if(e.isJsonPrimitive())allowed.add(e.getAsString());
        boolean excludeSelf=selectedAction!=null&&str(selectedAction,"action").equals("submit")&&Set.of("monk","butler").contains(str(object(state(),"pendingTask"),"role"));
        return contacts.stream().filter(c->flip||!c.key.equals("storyteller")).filter(c->!excludeSelf||!c.id.equals(viewer)).filter(c->!flip||selectedAction==null||!str(selectedAction,"action").equals("target")||allowed.contains(c.id)).toList();
    }
    private void initTargetSelection() {
        int w=innerRight-innerLeft;boolean hasText=selectedAction!=null&&str(selectedAction,"text").equals("$text");
        listTop=contentTop+(compact()?13:20);listBottom=contentBottom-(compact()?(hasText?44:24):hasText?77:49);
        if(hasText){assistantTextField=field(innerLeft,contentBottom-(compact()?41:73),w,compact()?18:23,"消息或裁定内容",keptAssistantText,600);assistantTextField.setChangedListener(value->keptAssistantText=value);}
        String label=selectedAction!=null&&str(selectedAction,"action").equals("nominate")?"确认提名":"确认提交";
        selectionConfirm=addDrawableChild(new PhoneButton(label,innerLeft,contentBottom-(compact()?19:24),w,compact()?18:23,true,this::confirmSelection));
        selectionConfirm.active=canConfirmSelection();
    }
    private boolean canConfirmSelection(){return waitingAction.isBlank()&&authorized(selectedAction)&&(!selectedAction.has("enabled")||bool(selectedAction,"enabled"))&&(!str(selectedAction,"target").equals("$target")||targetCount()>0)&&selectedTargets.size()==targetCount()&&selectedTargets.stream().allMatch(id->targetContacts().stream().anyMatch(c->c.id.equals(id)))&&ClientPlayNetworking.canSend(TabletopNet.Action.ID);}
    private void confirmSelection() {
        if(!canConfirmSelection())return;
        String target=str(selectedAction,"target").equals("$target")?String.join(",",selectedTargets):str(selectedAction,"target");
        String text=str(selectedAction,"text").equals("$text")?(assistantTextField==null?keptAssistantText:assistantTextField.getText()):str(selectedAction,"text");
        executeAction(selectedAction,target,text);showAssistant();
    }
    private String selectedNames(Collection<String> keys){return String.join("、",keys.stream().map(this::nameOf).toList());}
    private void toggleTarget(Contact contact) {
        if(!selectedTargets.remove(contact.id)) {
            int count=targetCount();
            if(count==1)selectedTargets.clear();
            if(selectedTargets.size()<count)selectedTargets.add(contact.id);else notice="最多选择 "+count+" 名玩家。";
        }
        if(selectionConfirm!=null)selectionConfirm.active=canConfirmSelection();
    }

    private JsonObject seatChoice(int number){
        for(JsonElement value:array(state(),"seatChoices"))if(value.isJsonObject()&&number(value.getAsJsonObject(),"number",0)==number)return value.getAsJsonObject();
        return new JsonObject();
    }
    private JsonObject seatAction(){
        for(JsonElement value:array("buttons"))if(value.isJsonObject()&&str(value.getAsJsonObject(),"action").equals("choose_seat"))return value.getAsJsonObject();
        return null;
    }
    private int seatChoiceColor(JsonObject choice,int number){return 0xff000000|(int)number(choice,"color",ClockSeats.color(number));}
    private String seatChoiceColorName(JsonObject choice,int number){String label=str(choice,"colorName");return label.isBlank()?ClockSeats.colorName(number):label;}
    private int seatCellHeight(){return Math.max(compact()?32:40,Math.min(62,Math.max(1,listBottom-listTop)/4));}
    private int seatRows(){int count=0;for(int i=1+seatChoicePage*8;i<=Math.min(12,8+seatChoicePage*8);i++)if(seatChoice(i).size()>0)count++;return (count+1)/2;}
    private int maxSeatScroll(){return Math.max(0,seatRows()*seatCellHeight()-Math.max(1,listBottom-listTop));}
    private void initSeatSelection(){
        int w=innerRight-innerLeft,gap=5,cellWidth=(w-gap)/2;
        listTop=contentTop+(compact()?15:28);listBottom=contentBottom-(compact()?35:48);
        for(int i=0;i<8;i++){
            SeatButton button=new SeatButton(i+1,innerLeft+(i%2)*(cellWidth+gap),listTop+(i/2)*seatCellHeight(),cellWidth,seatCellHeight()-4);
            seatButtons.add(button);addDrawableChild(button);
        }
        seatPager=smallButton("更多号码",innerLeft,contentBottom-(compact()?18:22),w,compact()?17:20,()->{
            seatChoicePage=1-seatChoicePage;listScroll=0;updateSeatButtons();
        });
    }
    private void updateSeatButtons(){
        if(page!=Page.SEATS)return;
        JsonObject action=seatAction();boolean choosing=bool(state(),"canChooseSeat")&&action!=null&&authorized(action)&&(!action.has("enabled")||bool(action,"enabled"));
        boolean ready=choosing&&waitingAction.isBlank()&&ClientPlayNetworking.canSend(TabletopNet.Action.ID);
        listScroll=Math.clamp(listScroll,0,maxSeatScroll());
        for(int i=0;i<seatButtons.size();i++){
            SeatButton button=seatButtons.get(i);button.number=i+1+seatChoicePage*8;JsonObject choice=seatChoice(button.number);
            button.setMessage(Text.literal(button.number+"号"));button.setY(listTop+(i/2)*seatCellHeight()-listScroll);
            button.visible=button.number<=12&&choice.size()>0;button.active=button.visible&&ready&&(bool(choice,"available")||bool(choice,"current"));
            String who=bool(choice,"current")?"你":str(choice,"ownerName");
            button.setTooltip(Tooltip.of(Text.literal(button.number+"号 · "+seatChoiceColorName(choice,button.number)+(bool(choice,"current")?" · 点击确认当前号码":who.isBlank()?" · 可选择":" · "+who))));
        }
        if(seatPager!=null){seatPager.visible=seatChoice(9).size()>0;seatPager.active=waitingAction.isBlank();seatPager.setMessage(Text.literal(seatChoicePage==0?"更多号码":"← 1–8"));}
    }
    private void chooseSeat(int number){
        JsonObject choice=seatChoice(number),action=seatAction();
        if(action==null||!bool(state(),"canChooseSeat")||(!bool(choice,"available")&&!bool(choice,"current"))||!waitingAction.isBlank())return;
        if(!ClientPlayNetworking.canSend(TabletopNet.Action.ID)){notice="连接未就绪。";return;}
        sendAuthorized(action,String.valueOf(number),"");updateSeatButtons();
    }

    private void sendMessage() {
        if(composeField==null)return;
        // Keep normal spaces exactly as typed. The same normalization runs on the server.
        String text=composeField.getText().replaceAll("[\\p{Cntrl}§]","");
        if(!text.equals(composeField.getText()))composeField.setText(text);
        drafts.put(conversation,text);
        if(text.isBlank())return;
        if(!ClientPlayNetworking.canSend(TabletopNet.Action.ID)){notice="当前连接无法发送消息。";return;}
        if(sending.containsKey(conversation))return;
        drafts.put(conversation,text);sending.put(conversation,new Sending(text,latestSequence,ticks));
        notice="正在发送…";TabletopClient.send("message",conversation,text);updateSendButton();
    }
    private void updateSendButton() {
        if(sendButton!=null){boolean pending=sending.containsKey(conversation);sendButton.active=!pending&&composeField!=null&&!composeField.getText().isBlank()&&ClientPlayNetworking.canSend(TabletopNet.Action.ID);sendButton.setMessage(Text.literal(pending?"…":"发送"));if(compact())chatBottom=Math.max(chatTop,contentBottom-20-(notice.isBlank()?4:14));}
    }
    private void createGroup() {
        if(keptGroupTargets.isBlank())return;
        groupsBeforeCreation=new HashSet<>(groups.stream().map(Group::key).toList());creatingGroup=true;
        notice="等待创建结果…";TabletopClient.send("group",keptGroupTargets,"");
    }
    private void saveDrafts() {
        if(composeField!=null)drafts.put(conversation,composeField.getText());
        if(searchField!=null)contactFilter=searchField.getText();
        if(assistantTextField!=null)keptAssistantText=assistantTextField.getText();
    }

    private void rebuildText() {
        if(textRenderer==null)return;
        int width=Math.max(40,innerRight-innerLeft);
        var text=new ArrayList<OrderedText>();
        List<String> assistantBody=new ArrayList<>();
        if(page==Page.IDENTITY){
            String role=str(state(),"roleName");
            if(str(state(),"storyteller").equals(viewer))assistantBody.add("说书人");
            else if(role.isBlank())assistantBody.add("身份尚未发放。");
            else {assistantBody.add(role);String ability=str(state(),"ability");if(!ability.isBlank())assistantBody.add(ability);}
        }else if(page==Page.SPY){
            JsonObject snapshot=object(state(),"spyGrimoire");
            if(snapshot.size()==0)assistantBody.add("魔典尚未确认。");
            else {
                assistantBody.add("第 "+number(snapshot,"night",0)+" 夜 · 已确认快照");
                assistantBody.add("状态以说书人确认时为准。");
                String master=str(snapshot,"master");
                if(!master.isBlank())assistantBody.add("管家主人 · "+snapshotPlayer(snapshot,master));
                List<String> bluffs=new ArrayList<>();
                for(JsonElement e:array(snapshot,"demonBluffs"))if(e.isJsonPrimitive()){
                    var bluff=local.tabletop.game.RoleCatalog.get(e.getAsString());
                    if(bluff!=null)bluffs.add(bluff.name());
                }
                if(!bluffs.isEmpty())assistantBody.add("恶魔诈唬 · "+String.join("、",bluffs));
                for(JsonElement e:array(snapshot,"seats"))if(e.isJsonObject()){
                    JsonObject row=e.getAsJsonObject();String real=str(row,"role"),shown=str(row,"shownRole");
                    var role=local.tabletop.game.RoleCatalog.get(real);
                    String heading=number(row,"seat",0)+"号 · "+nameOf(str(row,"uuid"))+"\n真实身份 · "+(role==null?"未分配":role.name());
                    String alignment=str(row,"alignment");
                    if(!alignment.isBlank())heading+=" · "+(alignment.equals("evil")?"邪恶":alignment.equals("good")?"善良":alignment);
                    if(!shown.isBlank()&&!shown.equals(real)){
                        var appearance=local.tabletop.game.RoleCatalog.get(shown);
                        heading+="\n假身份 · "+(appearance==null?"未分配":appearance.name());
                    }
                    List<String> flags=new ArrayList<>();
                    flags.add(bool(row,"dead")?"死亡":"存活");
                    flags.add(bool(row,"drunk")?"醉酒":"清醒");
                    flags.add(bool(row,"poisoned")?"中毒":"未中毒");
                    flags.add(bool(row,"used")?"能力已用":"能力未用");
                    if(bool(row,"redHerring"))flags.add("红鲱鱼");
                    if(bool(row,"safe"))flags.add("僧侣保护");
                    if(bool(row,"dead"))flags.add(bool(row,"ghostVote")?"幽灵票可用":"幽灵票已用");
                    heading+="\n"+String.join(" · ",flags);
                    String note=str(row,"note");
                    if(!note.isBlank())heading+="\n手记 · "+note;
                    assistantBody.add(heading);
                }
            }
        }else if(bool(state(),"tablePaused"))assistantBody.add("等待玩家重连。");
        else if(state().has("players")){
            JsonObject pending=object(state(),"pendingAction");
            if(pending.size()>0)assistantBody.add(str(pending,"title")+" · "+nameOf(str(pending,"owner")));
            for(JsonElement e:array(state(),"ranking"))if(e.isJsonObject()){
                JsonObject rank=e.getAsJsonObject();assistantBody.add(number(rank,"rank",0)+". "+nameOf(str(rank,"player"))+" · "+number(rank,"total",0)+" 分");
            }
        }else if(state().has("seats")&&object(state(),"pendingTask").size()==0){
            if(str(state(),"phase").equals("setup")&&!str(state(),"tableSizeLabel").isBlank())assistantBody.add(str(state(),"tableSizeLabel"));
            assistantBody.add(switch(str(state(),"phase")){case "night","first_night"->"在房间等待";case "day"->"自由讨论";case "finished"->"对局结束";default->"等待说书人开局";});
        }else if(flipGame())assistantBody.add("等待主持人开局。");
        for(String body:assistantBody)for(String paragraph:body.split("\n")) {
            text.addAll(textRenderer.wrapLines(Text.literal(paragraph),Math.max(36,width-8)));text.add(Text.empty().asOrderedText());
        }
        assistantLines=List.copyOf(text);assistantScroll=Math.min(assistantScroll,maxAssistantScroll());
        var layouts=new ArrayList<Bubble>();bubbleHeight=0;
        int maxWidth=Math.max(30,width-28);
        for(Chat chat:received.values())if(chat.key.equals(conversation)) {
            List<OrderedText> lines=textRenderer.wrapLines(Text.literal(chat.text),maxWidth-16);
            int actual=0;for(OrderedText line:lines)actual=Math.max(actual,textRenderer.getWidth(line));
            int bw=Math.min(maxWidth,Math.max(42,actual+16));
            int bh=20+Math.max(1,lines.size())*11;
            layouts.add(new Bubble(chat,List.copyOf(lines),bw,bh));bubbleHeight+=bh+8;
        }
        bubbles=List.copyOf(layouts);chatScroll=Math.min(chatScroll,maxChatScroll());
    }
    private int maxChatScroll(){return Math.max(0,bubbleHeight-Math.max(1,chatBottom-chatTop));}
    private int assistantHeight(){return assistantActions().size()*actionRowHeight()+(assistantLines.isEmpty()?0:12+assistantLines.size()*11);}
    private int maxAssistantScroll(){return Math.max(0,assistantHeight()-Math.max(1,assistantBottom-assistantTop));}
    private long latestIn(String key){return received.values().stream().filter(m->m.key.equals(key)).mapToLong(Chat::sequence).max().orElse(0);}
    private int unread(String key){long read=readThrough.getOrDefault(key,0L);return (int)received.values().stream().filter(m->m.key.equals(key)&&!m.outgoing&&m.sequence>read).count();}
    private int unreadTotal(){return (int)received.values().stream().filter(m->!m.outgoing&&m.sequence>readThrough.getOrDefault(m.key,0L)).count();}
    private String nameOf(String key) {
        if(key.equals("public"))return "公共讨论";
        for(Group g:groups)if(g.key.equals(key))return g.name.isBlank()?"私密小组 "+key:g.name;
        for(Contact c:contacts)if(c.key.equals(key)||c.id.equals(key))return c.name;
        if(key.equals("storyteller"))return "说书人";
        if(key.isBlank())return "等待确认";
        if(parseId(key)!=null)return "本桌玩家";
        return key;
    }
    private String snapshotPlayer(JsonObject snapshot,String id){
        for(JsonElement e:array(snapshot,"seats"))if(e.isJsonObject()){
            JsonObject row=e.getAsJsonObject();
            if(str(row,"uuid").equals(id))return number(row,"seat",0)+"号 · "+nameOf(id);
        }
        return nameOf(id);
    }
    private List<Row> rows() {
        if(page==Page.CARD_PLAYERS){
            if(!publicFlipCards())return List.of();var result=new ArrayList<Row>();
            for(var entry:object(state(),"players").entrySet())if(entry.getValue().isJsonObject()){String id=entry.getKey();JsonObject row=entry.getValue().getAsJsonObject();result.add(new Row(id,nameOf(id)+(id.equals(viewer)?"（你）":""),"累计 "+number(row,"total",0)+" · 本轮 "+number(row,"roundScore",0)+" · "+publicCardStatus(row,id),0,false));}
            return result;
        }
        if(page==Page.CONTACTS||page==Page.GROUP) {
            return contacts.stream().filter(c->!c.id.equals(viewer)).filter(c->page==Page.GROUP||c.name.toLowerCase(Locale.ROOT).contains(contactFilter.toLowerCase(Locale.ROOT)))
                .map(c->new Row(page==Page.GROUP?c.id:c.key,c.name,contactPreview(c),0,false)).toList();
        }
        Set<String> keys=new LinkedHashSet<>();keys.add("public");keys.add("storyteller");groups.forEach(g->keys.add(g.key));received.values().forEach(m->keys.add(m.key));
        var result=new ArrayList<Row>();
        for(String key:keys) {
            Chat last=received.descendingMap().values().stream().filter(m->m.key.equals(key)).findFirst().orElse(null);
            String preview=last==null?(key.equals("public")?"面向这一桌的公共讨论":"点击开始交流"):(last.outgoing?"你：":last.sender+"：")+last.text;
            result.add(new Row(key,nameOf(key),preview,last==null?0:last.sequence,key.equals("public")||groups.stream().anyMatch(g->g.key.equals(key))));
        }
        result.sort(Comparator.comparingLong(Row::latest).reversed());return result;
    }

    @Override public void tick() {
        ticks++;
        if(!waitingAction.isBlank()&&ticks-waitingSince>200){waitingAction="";notice="尚未收到操作结果，当前输入已保留。";updateAssistantButtons();updateSeatButtons();}
        if(ticks%60==0)TabletopClient.send("refresh","","");
        boolean expired=false;
        for(var entry:new ArrayList<>(sending.entrySet()))if(ticks-entry.getValue().tick>100){sending.remove(entry.getKey());expired=true;}
        if(expired){notice="发送尚未确认，输入已保留，可重试。";updateSendButton();}
    }
    @Override public boolean shouldPause(){return false;}
    @Override public void resize(MinecraftClient client,int width,int height){boolean atBottom=chatScroll>=maxChatScroll()-3;saveDrafts();super.resize(client,width,height);if(page==Page.CHAT&&atBottom)chatScroll=maxChatScroll();}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(confirmAction!=null){if(key==256)confirmAction=null;return true;}
        if(key==256){if(page==Page.HOME)close();else back();return true;}
        if(key==257||key==335) {
            if(page==Page.CHAT&&composeField!=null&&composeField.isFocused()){sendMessage();return true;}
            if(page==Page.GROUP){createGroup();return true;}
            if(page==Page.TARGETS){confirmSelection();return true;}
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean charTyped(char character,int modifiers){if(confirmAction!=null)return true;return super.charTyped(character,modifiers);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(confirmAction!=null)return true;
        if(x<innerLeft||x>innerRight||y<contentTop||y>contentBottom)return super.mouseScrolled(x,y,horizontal,vertical);
        int delta=(int)(vertical*26);
        switch(page) {
            case CHAT -> chatScroll=Math.clamp(chatScroll-delta,0,maxChatScroll());
            case ASSISTANT -> assistantScroll=Math.clamp(assistantScroll-delta,0,maxAssistantScroll());
            case TARGETS -> listScroll=Math.clamp(listScroll-delta,0,Math.max(0,targetContacts().size()*targetRowHeight()-(listBottom-listTop)));
            case SEATS -> {listScroll=Math.clamp(listScroll-delta,0,maxSeatScroll());updateSeatButtons();}
            case CARDS -> listScroll=Math.clamp(listScroll-delta,0,maxCardScroll());
            case HOME -> {homeScroll=Math.clamp(homeScroll-delta,0,Math.max(0,homeHeight-(contentBottom-contentTop)));for(int i=0;i<appButtons.size();i++)appButtons.get(i).setY(contentTop+homeIntroHeight()+(i/2)*homeRowHeight-homeScroll);}
            case IDENTITY,SPY -> listScroll=Math.clamp(listScroll-delta,0,Math.max(0,assistantLines.size()*11-(listBottom-listTop)));
            default -> listScroll=Math.clamp(listScroll-delta,0,Math.max(0,rows().size()*rowHeight()-(listBottom-listTop)));
        }
        return true;
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(confirmAction!=null){
            int by=confirmTop()+confirmHeight()-28,w=(innerRight-innerLeft-23)/2;
            if(button==0&&x>=innerLeft+7&&x<innerLeft+7+w&&y>=by&&y<by+21)confirmAction=null;
            else if(button==0&&x>=innerLeft+16+w&&x<innerRight-7&&y>=by&&y<by+21){JsonObject spec=confirmAction;confirmAction=null;sendAuthorized(spec,confirmTarget,confirmText);}
            return true;
        }
        if(super.mouseClicked(x,y,button))return true;
        if(button==0&&!compact()&&page==Page.ASSISTANT&&flipGame()&&state().has("players")&&x>=innerLeft&&x<innerRight&&y>=contentTop+60&&y<contentTop+106){showCards();return true;}
        if(button==0&&page==Page.ASSISTANT&&x>=innerLeft&&x<innerRight&&y>=assistantTop&&y<assistantBottom) {
            int index=((int)y-assistantTop+assistantScroll)/actionRowHeight();List<JsonObject> actions=assistantActions();
            if(index>=0&&index<actions.size()){assistantAction(actions.get(index));return true;}
        }
        if(button!=0||x<innerLeft||x>innerRight||y<listTop||y>=listBottom)return false;
        if(page==Page.CARDS){int index=(((int)y-listTop+listScroll)/25)*cardColumns()+((int)x-innerLeft)/25;if(index>=0&&index<publicCards().size()){cardSelection=index;detailReveal=false;}return true;}
        if(page==Page.TARGETS&&targetCount()>0) {
            int index=((int)y-listTop+listScroll)/targetRowHeight();List<Contact> available=targetContacts();
            if(index>=0&&index<available.size()){toggleTarget(available.get(index));return true;}
        }
        if(page==Page.CONVERSATIONS||page==Page.CONTACTS||page==Page.GROUP||page==Page.CARD_PLAYERS) {
            int index=((int)y-listTop+listScroll)/rowHeight();List<Row> list=rows();if(index<0||index>=list.size())return true;
            Row row=list.get(index);
            if(page==Page.CARD_PLAYERS)showPublicCards(row.key);
            else if(page==Page.GROUP) {
                LinkedHashSet<String> chosen=new LinkedHashSet<>();for(String member:keptGroupTargets.split(","))if(!member.isBlank())chosen.add(member.trim());
                if(!chosen.remove(row.key))chosen.add(row.key);keptGroupTargets=String.join(",",chosen);
            } else showChat(row.key);
            return true;
        }
        return false;
    }

    @Override public void render(DrawContext c,int mx,int my,float delta) {
        // The room remains visible around this narrow physical reading surface.
        rounded(c,left-4,top-4,phoneWidth+8,phoneHeight+8,19,0xff050a0e);
        rounded(c,left-2,top-2,phoneWidth+4,phoneHeight+4,17,0xff435662);
        rounded(c,left,top,phoneWidth,phoneHeight,15,0xff101e28);
        c.fill(left+phoneWidth/2-20,top+6,left+phoneWidth/2+20,top+9,0xff060a0d);
        rounded(c,left+phoneWidth/2+25,top+5,5,5,2,0xff060a0d);
        String title=switch(page){case HOME->"桌间手机";case CONVERSATIONS->"消息";case CONTACTS->"联系人";case CHAT->nameOf(conversation);case ASSISTANT->"游戏";case TARGETS->selectedAction==null?"选择玩家":str(selectedAction,"label");case SEATS->"选择号码与颜色";case GROUP->"创建小组";case IDENTITY->"身份";case SPY->"间谍魔典";case CARDS->cardOwner.equals(viewer)?"我的桌前牌":nameOf(cardOwner)+"的桌前牌";case CARD_PLAYERS->"本桌公开牌";};
        int titleWidth=innerRight-innerLeft-37;
        if(page==Page.CHAT)renderChatHeader(c);else c.drawText(textRenderer,Text.literal(trim(title,titleWidth)),innerLeft,top+(compact()?14:37),INK,false);
        c.fill(innerLeft,contentTop-7,innerRight,contentTop-6,0xff2a404c);
        switch(page) {
            case HOME -> renderHome(c);
            case CONVERSATIONS,CONTACTS,GROUP,CARD_PLAYERS -> renderRows(c,mx,my);
            case CHAT -> renderChat(c);
            case ASSISTANT -> renderAssistant(c,mx,my,delta);
            case TARGETS -> renderTargetSelection(c,mx,my);
            case SEATS -> renderSeatSelection(c,mx,my);
            case IDENTITY,SPY -> renderInformation(c);
            case CARDS -> renderCards(c,mx,my,delta);
        }
        if((!notice.isBlank()||!waitingAction.isBlank())&&(page==Page.CHAT||page==Page.GROUP||page==Page.TARGETS||page==Page.ASSISTANT)){
            String status=waitingAction.isBlank()?notice:"等待确认…";int y=page==Page.ASSISTANT?contentBottom-(compact()?11:22):page==Page.CHAT?contentBottom-40:contentBottom-(compact()?30:48);
            c.drawText(textRenderer,Text.literal(trim(status,innerRight-innerLeft)),innerLeft,y,MUTED,false);
            if(mx>=innerLeft&&mx<innerRight&&my>=y&&my<y+12)c.drawTooltip(textRenderer,Text.literal(status),mx,my);
        }
        c.fill(innerLeft,contentBottom+4,innerRight,contentBottom+5,0xff2a404c);
        super.render(c,mx,my,delta);
        if(page==Page.CARDS&&mx>=innerLeft&&mx<innerRight&&my>=top+35&&my<top+49)c.drawTooltip(textRenderer,Text.literal(title),mx,my);
        if(confirmAction!=null)renderConfirmation(c);
    }
    @Override public void renderBackground(DrawContext c,int mx,int my,float delta) {
        // The physical phone is drawn over the room; Screen.render must not blur it afterwards.
    }
    private void renderHome(DrawContext c) {
        if(compact())return;
        c.enableScissor(innerLeft,contentTop,innerRight,contentBottom);
        String status=ownPending()?"轮到你了":unreadTotal()>0?unreadTotal()+" 条新消息":session.isBlank()?"尚未入座":flipGame()?"七连翻":str(state(),"phase").equals("setup")&&!str(state(),"tableSizeLabel").isBlank()?str(state(),"tableSizeLabel"):"染钟楼谜团";
        c.drawText(textRenderer,Text.literal(trim(status,innerRight-innerLeft)),innerLeft+2,contentTop+5-homeScroll,ownPending()?TEAL:MUTED,false);
        c.disableScissor();
    }
    private void renderRows(DrawContext c,int mx,int my) {
        if(page==Page.GROUP)c.drawText(textRenderer,Text.literal("选择小组成员"),innerLeft,contentTop+3,MUTED,false);
        if(page==Page.CARD_PLAYERS)c.drawText(textRenderer,Text.literal("公开牌 · 点击玩家查看"),innerLeft,contentTop+4,MUTED,false);
        List<Row> list=rows();int h=Math.max(1,listBottom-listTop),rowHeight=rowHeight();listScroll=Math.min(listScroll,Math.max(0,list.size()*rowHeight-h));
        c.enableScissor(innerLeft,listTop,innerRight,listBottom);
        if(list.isEmpty())c.drawText(textRenderer,Text.literal("没有匹配的联系人"),innerLeft+5,listTop+12,MUTED,false);
        Set<String> chosen=new HashSet<>();if(page==Page.GROUP)for(String key:keptGroupTargets.split(","))if(!key.isBlank())chosen.add(key.trim());
        String hoveredPublicScore="";for(int i=0;i<list.size();i++) {
            Row row=list.get(i);int y=listTop+i*rowHeight-listScroll;if(y+rowHeight-1<listTop||y>=listBottom)continue;
            boolean hover=mx>=innerLeft&&mx<innerRight&&my>=y&&my<y+rowHeight-3&&my>=listTop&&my<listBottom;
            rounded(c,innerLeft,y+1,innerRight-innerLeft,rowHeight-4,6,hover?0xff203947:SURFACE);
            conversationAvatar(c,row.key,innerLeft+(compact()?4:7),y+(compact()?6:10),compact()?20:27);
            int textX=innerLeft+(compact()?29:41),w=innerRight-textX-8;
            int unread=page==Page.CONVERSATIONS?unread(row.key):0;
            boolean selected=page==Page.GROUP&&chosen.contains(row.key);
            c.drawText(textRenderer,Text.literal(trim(seatName(row.key,row.title),w-(unread>0||selected?20:0))),textX,y+(compact()?5:10),seatColor(row.key),false);
            c.drawText(textRenderer,Text.literal(trim(row.preview,w)),textX,y+(compact()?19:27),MUTED,false);
            if(unread>0){rounded(c,innerRight-22,y+7,16,16,8,0xff318980);String n=unread>9?"9+":String.valueOf(unread);c.drawText(textRenderer,Text.literal(n),innerRight-14-textRenderer.getWidth(n)/2,y+11,0xffffffff,false);}
            if(selected)c.drawText(textRenderer,Text.literal("已选"),innerRight-26,y+11,TEAL,false);
            if(hover)hoveredPublicScore=seatName(row.key,row.title)+(page==Page.CARD_PLAYERS?" · "+row.preview:"");
        }
        c.disableScissor();scrollbar(c,listTop,listBottom,list.size()*rowHeight,listScroll);
        if(!hoveredPublicScore.isBlank())c.drawTooltip(textRenderer,Text.literal(hoveredPublicScore),mx,my);
        if(page==Page.GROUP&&!compact())c.drawText(textRenderer,Text.literal(trim(chosen.isEmpty()?"尚未选择成员":selectedNames(chosen),innerRight-innerLeft)),innerLeft,contentBottom-39,chosen.isEmpty()?MUTED:TEAL,false);
    }
    private void renderChat(DrawContext c) {
        c.enableScissor(innerLeft,chatTop,innerRight,chatBottom);
        if(bubbles.isEmpty()) {
            c.drawText(textRenderer,Text.literal("此会话还没有消息"),innerLeft+4,chatTop+17,MUTED,false);
            c.drawText(textRenderer,Text.literal(conversation.equals("public")?"在下方输入，公开发送":"在下方输入，私密发送"),innerLeft+4,chatTop+34,MUTED,false);
        }
        int y=chatTop-chatScroll;
        for(Bubble bubble:bubbles) {
            if(y+bubble.height>=chatTop&&y<chatBottom) {
                int x=bubble.chat.outgoing?innerRight-26-bubble.width:innerLeft+26;
                UUID senderUuid=bubble.chat.outgoing?parseId(viewer):senderId(bubble.chat);
                playerAvatar(c,senderUuid,bubble.chat.outgoing?innerRight-21:innerLeft+1,y+2,20,false);
                rounded(c,x,y,bubble.width,bubble.height,7,bubble.chat.outgoing?OUTGOING:INCOMING);
                c.fill(bubble.chat.outgoing?x+bubble.width-1:x-3,y+9,bubble.chat.outgoing?x+bubble.width+3:x+1,y+13,bubble.chat.outgoing?OUTGOING:INCOMING);
                String sender=bubble.chat.outgoing?"你":bubble.chat.sender;
                c.drawText(textRenderer,Text.literal(trim(sender,bubble.width-16)),x+8,y+6,bubble.chat.outgoing?0xff9de6db:0xffa4cad9,false);
                for(int line=0;line<bubble.lines.size();line++)c.drawText(textRenderer,bubble.lines.get(line),x+8,y+19+line*11,INK,false);
            }
            y+=bubble.height+8;
        }
        c.disableScissor();scrollbar(c,chatTop,chatBottom,bubbleHeight,chatScroll);
    }

    private void renderChatHeader(DrawContext c) {
        if(compact()){c.drawText(textRenderer,Text.literal(trim(nameOf(conversation),innerRight-innerLeft-37)),innerLeft,top+14,INK,false);return;}
        conversationAvatar(c,conversation,innerLeft,top+30,23);
        int x=innerLeft+31,w=innerRight-x-37;
        c.drawText(textRenderer,Text.literal(trim(nameOf(conversation),w)),x,top+31,INK,false);
        UUID id=contactId(conversation);
        String detail=conversation.equals("public")?"这一桌 · 公共讨论":isGroup(conversation)?"私密小组 · "+groupMembers(conversation)+" 人":id==null?"私密会话":online(id)?"在线 · 私密会话":"离线 · 私密会话";
        c.drawText(textRenderer,Text.literal(trim(detail,w)),x,top+44,MUTED,false);
    }

    private boolean isGroup(String key){return groups.stream().anyMatch(g->g.key.equals(key));}
    private String contactPreview(Contact contact){
        if(contact.key.equals("storyteller"))return "私聊说书人";
        for(JsonElement e:array(state(),"seats"))if(e.isJsonObject()&&str(e.getAsJsonObject(),"uuid").equals(contact.id)){
            JsonObject seat=e.getAsJsonObject();return bool(seat,"dead")?"死亡 · 幽灵票"+(bool(seat,"ghostVote")?"可用":"已用"):"存活 · 私聊";
        }
        return "私聊";
    }
    private void renderInformation(DrawContext c){
        int full=assistantLines.size()*11,h=Math.max(1,listBottom-listTop);listScroll=Math.clamp(listScroll,0,Math.max(0,full-h));
        c.enableScissor(innerLeft,listTop,innerRight,listBottom);
        for(int i=0;i<assistantLines.size();i++){int y=listTop+i*11-listScroll;if(y+11>=listTop&&y<listBottom)c.drawText(textRenderer,assistantLines.get(i),innerLeft+2,y,i==0?TEAL:INK,false);}
        c.disableScissor();scrollbar(c,listTop,listBottom,full,listScroll);
    }
    private int groupMembers(String key){return groups.stream().filter(g->g.key.equals(key)).findFirst().map(g->g.members.size()).orElse(0);}
    private static UUID parseId(String value){try{return UUID.fromString(value);}catch(IllegalArgumentException ex){return null;}}
    private PlayerListEntry onlineEntry(UUID id){return id==null||client==null||client.getNetworkHandler()==null?null:client.getNetworkHandler().getPlayerListEntry(id);}
    private boolean online(UUID id){return onlineEntry(id)!=null;}
    private UUID contactId(String key) {
        for(Contact contact:contacts)if(contact.key.equals(key)||contact.name.equals(key)||contact.id.equals(key))return parseId(contact.id);
        UUID id=parseId(key);if(id!=null)return id;
        if(client!=null&&client.getNetworkHandler()!=null)for(PlayerListEntry entry:client.getNetworkHandler().getPlayerList())if(entry.getProfile().getName().equals(key))return entry.getProfile().getId();
        return null;
    }
    private UUID senderId(Chat message) {
        UUID id=contactId(message.sender);
        // A direct conversation has exactly one peer, even if its displayed name changed.
        return id!=null?id:message.key.equals("public")||isGroup(message.key)?null:contactId(message.key);
    }
    private void conversationAvatar(DrawContext c,String key,int x,int y,int size) {
        if(key.equals("public")||isGroup(key)) {
            int color=key.equals("public")?0xff2b6681:0xff695483;
            rounded(c,x,y,size,size,Math.min(8,size/3),color);
            int cx=x+size/2,cy=y+size/2,fg=INK;
            if(key.equals("public")) {
                // A speech bubble distinguishes public discussion from a person's skin.
                rounded(c,x+size/5,y+size/4,size*3/5,size/2,3,fg);
                c.fill(x+size/4,y+size*2/3,x+size/4+3,y+size*4/5,fg);
                for(int i=-1;i<=1;i++)c.fill(cx+i*4,cy-1,cx+i*4+2,cy+1,color);
            } else {
                rounded(c,cx-3,y+size/5,6,6,3,fg);rounded(c,cx-6,cy+1,12,6,3,fg);
                rounded(c,x+3,cy-3,4,4,2,fg);rounded(c,x+2,cy+3,5,4,2,fg);
                rounded(c,x+size-7,cy-3,4,4,2,fg);rounded(c,x+size-7,cy+3,5,4,2,fg);
            }
        } else playerAvatar(c,contactId(key),x,y,size,true);
    }
    private void playerAvatar(DrawContext c,UUID id,int x,int y,int size,boolean status) {
        rounded(c,x-1,y-1,size+2,size+2,3,0xff476373);
        if(id!=null) {
            PlayerListEntry entry=onlineEntry(id);
            // Minecraft's helper draws the actual 8x8 head and its transparent hat layer.
            PlayerSkinDrawer.draw(c,entry==null?DefaultSkinHelper.getSkinTextures(id):entry.getSkinTextures(),x,y,size);
        } else {
            // Unknown UUIDs use a neutral person symbol, never an invented player skin.
            rounded(c,x,y,size,size,2,SURFACE);rounded(c,x+size/2-3,y+size/5,6,6,3,MUTED);
            rounded(c,x+size/4,y+size/2,size/2,size/3,3,MUTED);
        }
        if(status&&id!=null){int dot=Math.max(4,size/5);rounded(c,x+size-dot,y+size-dot,dot+2,dot+2,dot/2+1,0xff101e28);rounded(c,x+size-dot+1,y+size-dot+1,dot,dot,dot/2,online(id)?0xff63d98c:0xff6b7c86);}
    }
    private void renderAssistant(DrawContext c,int mx,int my,float delta) {
        if(flipGame())renderFlipStatus(c,mx,my,delta);
        JsonObject vote=voteState();
        if(vote.entrySet().isEmpty())renderTaskCard(c,mx,my);
        if(!vote.entrySet().isEmpty()) {
            rounded(c,innerLeft,contentTop,innerRight-innerLeft,voteHeaderHeight(),7,0xff203d45);
            String nominee=str(vote,"nominee");playerAvatar(c,contactId(nominee),innerLeft+4,contentTop+4,20,false);
            c.drawText(textRenderer,Text.literal(trim("投票："+nameOf(nominee),innerRight-innerLeft-32)),innerLeft+29,contentTop+6,INK,false);
            String time=bool(state(),"tablePaused")?"等待玩家重连":"等待说书人计票";
            if(!compact())c.drawText(textRenderer,Text.literal(trim(time,innerRight-innerLeft-32)),innerLeft+29,contentTop+18,MUTED,false);
            JsonElement own=vote.get("myVote");
            String ownStatus=own==null||own.isJsonNull()?"未点默认不支持":own.getAsBoolean()?"你已支持 · 计票前可改":"你已选择不支持";
            if(vote.has("mayVote")&&!bool(vote,"mayVote"))ownStatus="等待玩家重连";
            c.drawText(textRenderer,Text.literal(trim(ownStatus,innerRight-innerLeft-8)),innerLeft+4,contentTop+(compact()?18:31),TEAL,false);
        }
        c.enableScissor(innerLeft,assistantTop,innerRight,assistantBottom);
        List<JsonObject> actions=assistantActions();int w=innerRight-innerLeft;
        String hoverReason="";
        for(int i=0;i<actions.size();i++) {
            int row=actionRowHeight(),y=assistantTop+i*row-assistantScroll;if(y+row-2<=assistantTop||y>=assistantBottom)continue;
            JsonObject spec=actions.get(i);boolean hover=mx>=innerLeft&&mx<innerRight&&my>=Math.max(y,assistantTop)&&my<Math.min(y+row-3,assistantBottom);
            boolean enabled=waitingAction.isBlank()&&(!spec.has("enabled")||bool(spec,"enabled"));rounded(c,innerLeft,y,w,row-3,6,hover&&enabled?0xff294653:SURFACE);
            String label=str(spec,"action").equals("nominate")?"发起提名":str(spec,"label");
            c.drawText(textRenderer,Text.literal(trim(label,w-23)),innerLeft+7,y+(compact()?7:12),enabled?INK:MUTED,false);
            String hint=str(spec,"action").equals("group")?"点击联系人建立小组":str(spec,"target").equals("$target")?"选择玩家后确认":str(spec,"text").equals("$text")?"填写内容后确认":"点击执行";
            if(!waitingAction.isBlank())hint="等待上一步操作结果";
            else if(!enabled)hint=str(spec,"reason").isBlank()?"当前条件尚未满足":str(spec,"reason");
            if(hover&&!enabled)hoverReason=hint;
            c.drawText(textRenderer,Text.literal("›"),innerRight-12,y+(compact()?7:12),TEAL,false);
        }
        int infoY=assistantTop+actions.size()*actionRowHeight()-assistantScroll;
        for(int i=0;i<assistantLines.size();i++){int y=infoY+12+i*11;if(y+11>=assistantTop&&y<assistantBottom)c.drawText(textRenderer,assistantLines.get(i),innerLeft+2,y,INK,false);}
        c.disableScissor();scrollbar(c,assistantTop,assistantBottom,assistantHeight(),assistantScroll);
        if(!hoverReason.isBlank())c.drawTooltip(textRenderer,Text.literal(hoverReason),mx,my);
    }
    private int taskCardHeight(){
        if(object(state(),"pendingTask").entrySet().isEmpty()||flipGame())return 0;
        if(compact())return 18;
        return Math.min(64,Math.max(43,contentBottom-contentTop-42));
    }
    private void renderTaskCard(DrawContext c,int mx,int my){
        int height=taskCardHeight();if(height==0)return;JsonObject task=object(state(),"pendingTask");int width=innerRight-innerLeft;
        rounded(c,innerLeft,contentTop,width,height,7,0xff25454a);
        var role=local.tabletop.game.RoleCatalog.get(str(task,"role"));
        String roleName=role!=null?role.name():switch(str(task,"role")){case "minion_info"->"爪牙互认";case "demon_info"->"恶魔信息";case "scarlet_woman"->"身份通知";default->"夜间行动";};
        boolean submitted=str(task,"status").equals("awaiting_confirmation");
        c.drawText(textRenderer,Text.literal(trim((submitted?"已回应 · ":"轮到你 · ")+roleName,width-12)),innerLeft+6,contentTop+7,TEAL,false);
        String help=submitted?"等待说书人确认":str(task,"help");
        var lines=textRenderer.wrapLines(Text.literal(help),Math.max(20,width-12));int visible=Math.max(1,(height-25)/11);
        if(!compact()){c.enableScissor(innerLeft+6,contentTop+22,innerRight-6,contentTop+height-4);for(int i=0;i<Math.min(visible,lines.size());i++)c.drawText(textRenderer,lines.get(i),innerLeft+6,contentTop+22+i*11,INK,false);c.disableScissor();}
        if((compact()||lines.size()>visible)&&mx>=innerLeft&&mx<innerRight&&my>=contentTop&&my<contentTop+height)c.drawTooltip(textRenderer,Text.literal(help),mx,my);
    }
    private JsonObject ownFlipRow(){return object(object(state(),"players"),viewer);}
    /** This page reads only the existing public Flip 7 rows; Clocktower uses seats instead. */
    private boolean publicFlipCards(){return state().has("round")&&state().has("flowState")&&state().has("players")&&!state().has("seats");}
    private JsonObject publicCardRow(){return publicFlipCards()?object(object(state(),"players"),cardOwner):new JsonObject();}
    private JsonArray publicCards(){JsonArray cards=new JsonArray();for(JsonElement e:array(publicCardRow(),"cards"))if(e.isJsonPrimitive()&&FLIP_CARD_IDS.contains(e.getAsString()))cards.add(e);return cards;}
    private String publicCardStatus(JsonObject row,String owner){return switch(str(row,"status")){case "busted"->"爆牌";case "stayed"->"已收手";case "frozen"->"已冻结";default->bool(state(),"roundOver")?"已结算":str(state(),"turn").equals(owner)?"正在翻牌":"等待";};}
    private boolean flipGame(){return state().has("players")||str(state(),"game").equals("flip7");}
    private int flipHeaderHeight(){return compact()?24:state().has("players")?106:74;}
    private String hostName(){return contacts.stream().filter(c->c.key.equals("storyteller")).findFirst().map(Contact::name).orElse("主持人");}
    private boolean isHost(){return contacts.stream().anyMatch(c->c.key.equals("storyteller")&&c.id.equals(viewer));}
    private String flipNextStep(){
        if(bool(state(),"tablePaused"))return "有人离线 · 等待重连后继续";
        if(!state().has("players"))return bool(state(),"readyToStart")?(isHost()?"人数已齐 · 请在下方确认开局":"人数已齐 · 等待主持人确认开局"):"等待入座 · 还需 "+Math.max(0,number(state(),"minimumPlayers",3)-number(state(),"playerCount",0))+" 名玩家";
        if(bool(state(),"finished"))return "获胜："+nameOf(str(state(),"winner"));
        if(bool(state(),"roundOver")){if(bool(state(),"tiedAtThreshold"))return "最高分并列 · 全桌继续加轮";return isHost()?"已结算 · 主持人可开始下一轮":"已结算 · 等待下一轮";}
        JsonObject pending=object(state(),"pendingAction");if(pending.size()>0)return (str(pending,"owner").equals(viewer)?"请你":"等待 "+nameOf(str(pending,"owner")))+"选择"+str(pending,"title")+"目标";
        return str(state(),"turn").equals(viewer)?"轮到你 · 翻牌或收手":"等待 "+nameOf(str(state(),"turn"))+" 翻牌或收手";
    }
    private String cardLabel(String id){if(id.startsWith("number_"))return id.substring(7);if(id.startsWith("plus_"))return "+"+id.substring(5);return switch(id){case "times_two"->"×2";case "freeze"->"冻结";case "flip_three"->"连翻三张";case "second_chance"->"二次机会";default->"牌";};}
    private void renderFlipStatus(DrawContext c,int mx,int my,float delta){
        int w=innerRight-innerLeft;rounded(c,innerLeft,contentTop,w,flipHeaderHeight(),7,0xff243e43);
        if(compact()){
            c.drawText(textRenderer,Text.literal(trim(state().has("players")?"七连翻 · 第 "+number(state(),"round",0)+" 轮":"七连翻 · 入座准备",w-12)),innerLeft+6,contentTop+3,TEAL,false);
            String next=flipNextStep();c.drawText(textRenderer,Text.literal(trim(next,w-12)),innerLeft+6,contentTop+14,INK,false);
            if(mx>=innerLeft&&mx<innerRight&&my>=contentTop&&my<contentTop+24)c.drawTooltip(textRenderer,Text.literal(next),mx,my);
            return;
        }
        c.drawText(textRenderer,Text.literal(trim(state().has("players")?"七连翻 · 第 "+number(state(),"round",0)+" 轮":"七连翻 · 入座准备",w-12)),innerLeft+6,contentTop+6,TEAL,false);
        String next=flipNextStep();c.drawText(textRenderer,Text.literal(trim(next,w-12)),innerLeft+6,contentTop+20,INK,false);
        if(!state().has("players")){
            c.drawText(textRenderer,Text.literal(trim("人数 "+number(state(),"playerCount",contacts.size())+" / "+number(state(),"maximumPlayers",18)+" · 至少3人",w-12)),innerLeft+6,contentTop+36,INK,false);
            c.drawText(textRenderer,Text.literal(trim(isHost()?"你是主持人 · 也参与翻牌":"主持人："+hostName(),w-12)),innerLeft+6,contentTop+53,MUTED,false);
        }else{
            JsonObject own=ownFlipRow(),score=object(own,"scoreDetails");String status=switch(str(own,"status")){case "busted"->"爆牌 · 本轮0分";case "stayed"->"已收手";case "frozen"->"已冻结";default->bool(state(),"roundOver")?"已结算":"当前可保留";};
            c.drawText(textRenderer,Text.literal(trim(status+" "+number(own,"roundScore",0)+" · 总分 "+number(own,"total",0),w-12)),innerLeft+6,contentTop+34,INK,false);
            boolean fresh=ticks-revealTick<60&&!revealedCard.isBlank();
            String arithmetic=fresh?"刚翻到："+cardLabel(revealedCard)+(bool(score,"busted")?" · 爆牌":""):bool(score,"busted")?"数字重复，整轮归零":number(score,"numberTotal",0)+" × "+number(score,"multiplier",1)+" + "+number(score,"modifierTotal",0)+" + "+number(score,"sevenBonus",0)+" = "+number(score,"roundScore",0);
            c.drawText(textRenderer,Text.literal(trim(arithmetic,w-(fresh?35:12))),innerLeft+6,contentTop+47,MUTED,false);
            if(fresh){var revealItem=TabletopMod.ITEMS.get(revealedCard);if(revealItem!=null){int x=innerRight-23,y=contentTop+42;rounded(c,x-1,y-1,19,19,3,0xff387872);float progress=Math.clamp((ticks-revealTick+delta)/12f,0f,1f);c.getMatrices().push();c.getMatrices().translate(x+8,y+(1-progress)*6,0);c.getMatrices().scale(Math.max(.08f,progress),1f,1f);c.drawItem(cardIcons.computeIfAbsent(revealedCard,k->new ItemStack(revealItem)),-8,0);c.getMatrices().pop();if(mx>=x-1&&mx<x+18&&my>=y-1&&my<y+18)c.drawTooltip(textRenderer,Text.literal("刚翻到 · "+cardLabel(revealedCard)),mx,my);}}
            JsonArray cards=array(own,"cards");int limit=Math.max(1,Math.min(8,(w-12)/22)),index=0;
        if(cards.size()>limit)limit=Math.max(1,limit-1);
            for(JsonElement e:cards)if(e.isJsonPrimitive()&&index<limit){String id=e.getAsString();var item=TabletopMod.ITEMS.get(id);if(item!=null){int x=innerLeft+6+index*22,y=contentTop+63;ItemStack stack=cardIcons.computeIfAbsent(id,k->new ItemStack(item));if(id.equals(revealedCard)&&ticks-revealTick<32)rounded(c,x-1,y-1,20,29,3,0xff387872);c.drawItem(stack,x,y);String label=cardLabel(id);c.drawText(textRenderer,Text.literal(trim(label,21)),x,y+17,INK,false);if(mx>=x&&mx<x+21&&my>=y&&my<y+28)c.drawTooltip(textRenderer,Text.literal(label+" · 点击查看大牌"),mx,my);index++;}}
            if(cards.size()>index)c.drawText(textRenderer,Text.literal("+"+(cards.size()-index)),innerLeft+6+index*22,contentTop+70,MUTED,false);
            if(index==0)c.drawText(textRenderer,Text.literal("尚无桌前牌"),innerLeft+6,contentTop+69,MUTED,false);
            c.drawText(textRenderer,Text.literal(trim("数字 "+number(own,"numberCount",0)+" / 7 · 点牌查看"+(bool(own,"secondChance")?" · 有保护":""),w-12)),innerLeft+6,contentTop+93,MUTED,false);
        }
        if(mx>=innerLeft+4&&mx<innerRight-4&&my>=contentTop+18&&my<contentTop+32)c.drawTooltip(textRenderer,Text.literal(next),mx,my);
    }
    private int cardColumns(){return Math.max(1,(innerRight-innerLeft)/25);}
    private int cardPreviewWidth(){return innerRight-innerLeft>=175?64:56;}
    private int cardPreviewHeight(){return cardPreviewWidth()*3/2;}
    private String cardShortLabel(String id){return switch(id){case "freeze"->"冻";case "flip_three"->"翻3";case "second_chance"->"护";default->cardLabel(id);};}
    private int maxCardScroll(){int rows=(publicCards().size()+cardColumns()-1)/cardColumns();return Math.max(0,rows*25-Math.max(1,listBottom-listTop));}
    private void cycleCard(int direction){detailReveal=false;int count=publicCards().size();if(count>0)cardSelection=Math.floorMod(cardSelection+direction,count);}
    private String cardDescription(String id){
        if(id.startsWith("number_"))return id.equals("number_0")?"0不加分，也算一种数字。":"数字牌。拿到相同数字会爆牌；七种不同数字奖励15分。";
        if(id.startsWith("plus_"))return "数字计分后额外加"+id.substring(5)+"分，不被×2翻倍。";
        return switch(id){case "times_two"->"只将数字之和翻倍；不翻倍加分牌和15分奖励。";case "freeze"->"指定活跃玩家冻结，他保留分数并退出本轮。";case "flip_three"->"指定活跃玩家逐张翻三张；爆牌或七种数字提前停止。";case "second_chance"->"挡一次数字重复。用后弃掉保护与重复牌，不补抽。";default->"公开的桌前牌。";};
    }
    private void renderCards(DrawContext c,int mx,int my,float delta){
        if(!publicFlipCards()){c.drawText(textRenderer,Text.literal("当前没有七连翻公开牌"),innerLeft,contentTop+16,MUTED,false);return;}
        JsonObject publicRow=publicCardRow();JsonArray cards=publicCards();int w=innerRight-innerLeft;
        String summary="累计 "+number(publicRow,"total",0)+" · 本轮 "+number(publicRow,"roundScore",0)+" · "+publicCardStatus(publicRow,cardOwner);
        c.drawText(textRenderer,Text.literal(trim("累计 "+number(publicRow,"total",0)+" 分",w-61)),innerLeft,contentTop+3,INK,false);
        c.drawText(textRenderer,Text.literal(trim("本轮 "+number(publicRow,"roundScore",0)+" · "+publicCardStatus(publicRow,cardOwner),w-61)),innerLeft,contentTop+16,MUTED,false);
        if(mx>=innerLeft&&mx<innerRight-59&&my>=contentTop&&my<contentTop+25)c.drawTooltip(textRenderer,Text.literal(summary),mx,my);
        boolean own=cardOwner.equals(viewer),fresh=own&&detailReveal&&FLIP_CARD_IDS.contains(revealedCard)&&ticks-revealTick<80;
        if(cards.isEmpty()&&!fresh){c.drawText(textRenderer,Text.literal("这一轮还没有桌前牌"),innerLeft,contentTop+47,MUTED,false);return;}
        cardSelection=Math.clamp(cardSelection,0,Math.max(0,cards.size()-1));String id=fresh?revealedCard:cards.get(cardSelection).getAsString();
        c.drawText(textRenderer,Text.literal(trim((fresh?"刚翻到 · ":"")+cardLabel(id)+(fresh?"":" · "+(cardSelection+1)+" / "+cards.size()),w)),innerLeft,contentTop+29,TEAL,false);
        int cardWidth=cardPreviewWidth(),cardHeight=cardPreviewHeight(),frameX=innerLeft+2,frameY=contentTop+44;
        rounded(c,frameX-2,frameY-2,cardWidth+4,cardHeight+4,5,0xff45615e);
        if(TabletopMod.ITEMS.containsKey(id)){float progress=own&&id.equals(revealedCard)?Math.clamp((ticks-revealTick+delta)/12f,0f,1f):1f;float horizontal=Math.max(.08f,progress);c.getMatrices().push();c.getMatrices().translate(frameX+cardWidth/2f,frameY+(1-progress)*8,0);c.getMatrices().scale(horizontal,1f,1f);c.drawTexture(Identifier.of("tabletop","textures/item/"+id+".png"),-cardWidth/2,0,0,0,cardWidth,cardHeight,cardWidth,cardHeight);c.getMatrices().pop();}
        int descriptionX=frameX+cardWidth+8,descriptionWidth=Math.max(24,innerRight-descriptionX),descriptionLines=Math.max(1,(cardHeight-4)/11);
        List<OrderedText> description=textRenderer.wrapLines(Text.literal(cardDescription(id)),descriptionWidth);for(int i=0;i<Math.min(descriptionLines,description.size());i++)c.drawText(textRenderer,description.get(i),descriptionX,frameY+2+i*11,INK,false);
        if(description.size()>descriptionLines)c.drawText(textRenderer,Text.literal("悬停查看"),descriptionX,frameY+cardHeight-8,MUTED,false);
        listScroll=Math.clamp(listScroll,0,maxCardScroll());c.enableScissor(innerLeft,listTop,innerRight,listBottom);
        String hoverCard="";
        for(int i=0;i<cards.size();i++){int x=innerLeft+(i%cardColumns())*25,y=listTop+(i/cardColumns())*25-listScroll;if(y+24<listTop||y>=listBottom)continue;String card=cards.get(i).getAsString();rounded(c,x,y,23,23,3,i==cardSelection?0xff397875:SURFACE);c.drawText(textRenderer,Text.literal(cardShortLabel(card)),x+2,y+8,i==cardSelection?INK:MUTED,false);if(mx>=x&&mx<x+23&&my>=Math.max(y,listTop)&&my<Math.min(y+23,listBottom))hoverCard=cardLabel(card);}
        c.disableScissor();scrollbar(c,listTop,listBottom,((cards.size()+cardColumns()-1)/cardColumns())*25,listScroll);
        if(mx>=descriptionX&&mx<innerRight&&my>=frameY&&my<frameY+cardHeight)c.drawTooltip(textRenderer,Text.literal(cardDescription(id)),mx,my);
        else if(!hoverCard.isBlank())c.drawTooltip(textRenderer,Text.literal(hoverCard),mx,my);
    }
    private int confirmHeight(){return Math.min(170,phoneHeight-12);}
    private int confirmTop(){return top+(phoneHeight-confirmHeight())/2;}
    private void renderConfirmation(DrawContext c){
        c.fill(innerLeft-2,top+3,innerRight+2,top+phoneHeight-3,0xda071217);int y=confirmTop(),h=confirmHeight(),w=innerRight-innerLeft;
        rounded(c,innerLeft,y,w,h,7,INCOMING);c.drawText(textRenderer,Text.literal(trim("确认 · "+str(confirmAction,"label"),w-14)),innerLeft+7,y+9,TEAL,false);
        List<OrderedText> lines=textRenderer.wrapLines(Text.literal(str(confirmAction,"confirm")),w-14);c.enableScissor(innerLeft+7,y+25,innerRight-7,y+h-35);for(int i=0;i<lines.size();i++)c.drawText(textRenderer,lines.get(i),innerLeft+7,y+25+i*11,INK,false);c.disableScissor();
        int by=y+h-28,bw=(w-23)/2;rounded(c,innerLeft+7,by,bw,21,5,SURFACE);rounded(c,innerLeft+16+bw,by,bw,21,5,OUTGOING);
        c.drawText(textRenderer,Text.literal("返回"),innerLeft+7+bw/2-8,by+6,INK,false);c.drawText(textRenderer,Text.literal("确认"),innerLeft+16+bw+bw/2-8,by+6,INK,false);
    }
    private void renderTargetSelection(DrawContext c,int mx,int my) {
        int count=targetCount(),w=innerRight-innerLeft;
        String prompt=count==0?"此操作无需选择玩家":"请选择 "+count+" 名玩家 · 已选 "+selectedTargets.size();
        if(selectedAction!=null&&str(selectedAction,"action").equals("target"))prompt=str(object(state(),"pendingAction"),"title")+" · "+prompt;
        c.drawText(textRenderer,Text.literal(trim(prompt,w)),innerLeft,contentTop+4,MUTED,false);
        List<Contact> available=count==0?List.of():targetContacts();
        int rowHeight=targetRowHeight(),full=available.size()*rowHeight;listScroll=Math.clamp(listScroll,0,Math.max(0,full-Math.max(1,listBottom-listTop)));
        c.enableScissor(innerLeft,listTop,innerRight,listBottom);
        for(int i=0;i<available.size();i++) {
            Contact contact=available.get(i);int y=listTop+i*rowHeight-listScroll;if(y+rowHeight-2<=listTop||y>=listBottom)continue;
            boolean selected=selectedTargets.contains(contact.id),hover=mx>=innerLeft&&mx<innerRight&&my>=Math.max(listTop,y)&&my<Math.min(listBottom,y+rowHeight-3);
            rounded(c,innerLeft,y,w,rowHeight-3,6,selected?0xff21545a:hover?0xff294653:SURFACE);
            playerAvatar(c,parseId(contact.id),innerLeft+4,y+(compact()?3:7),compact()?16:25,true);
            c.drawText(textRenderer,Text.literal(trim(seatName(contact.id,contact.name),w-(compact()?45:66))),innerLeft+(compact()?26:38),y+(compact()?7:8),seatColor(contact.id),false);
                String hint=selected?"已选择":contact.id.equals(viewer)?"选择自己":"点击选择";JsonObject row=object(object(state(),"players"),contact.id);if(row.size()>0)hint+=(bool(row,"secondChance")?" · 有保护":"")+" · "+number(row,"roundScore",0)+"分";
                if(!compact())c.drawText(textRenderer,Text.literal(trim(hint,w-45)),innerLeft+38,y+23,selected?TEAL:MUTED,false);
            if(selected)c.drawText(textRenderer,Text.literal("✓"),innerRight-16,y+(compact()?7:15),TEAL,false);
        }
        if(available.isEmpty()&&count>0)c.drawText(textRenderer,Text.literal("没有可选择的本桌玩家"),innerLeft+3,listTop+8,MUTED,false);
        c.disableScissor();scrollbar(c,listTop,listBottom,full,listScroll);
        String summary=selectedTargets.isEmpty()?"尚未选择玩家":selectedNames(selectedTargets);
        if(count==0)summary="确认后提交当前操作";
        if(!compact())c.drawText(textRenderer,Text.literal(trim(summary,w)),innerLeft,contentBottom-39,selectedTargets.isEmpty()?MUTED:TEAL,false);
    }
    private void renderSeatSelection(DrawContext c,int mx,int my){
        int number=(int)number(state(),"ownSeat",0);JsonObject own=seatChoice(number);
        String current=number>0?"当前 · "+number+"号 · "+seatChoiceColorName(own,number):"选择一个空号";
        c.drawText(textRenderer,Text.literal(trim(current,innerRight-innerLeft)),innerLeft,contentTop+4,number>0?seatChoiceColor(own,number):MUTED,false);
        boolean flipSeats=str(state(),"game").equals("flip7");
        if(!notice.isBlank()||!waitingAction.isBlank()||flipSeats){
            String status=waitingAction.isBlank()?(notice.isBlank()?"红色1号起牌 · 按1→8顺时针":notice):"正在选择…";int y=contentBottom-(compact()?30:43);
            c.drawText(textRenderer,Text.literal(trim(status,innerRight-innerLeft)),innerLeft,y,MUTED,false);
            if(mx>=innerLeft&&mx<innerRight&&my>=y&&my<y+11)c.drawTooltip(textRenderer,Text.literal(status),mx,my);
        }
        scrollbar(c,listTop,listBottom,seatRows()*seatCellHeight(),listScroll);
    }
    private void scrollbar(DrawContext c,int start,int end,int full,int scroll) {
        int h=end-start;if(h<=0||full<=h)return;
        int thumb=Math.max(10,h*h/full),y=start+(h-thumb)*scroll/Math.max(1,full-h);
        c.fill(innerRight-2,y,innerRight,y+thumb,0xff47838a);
    }

    private boolean ownPending(){JsonObject own=ownFlipRow();return state().has("pendingTask")||bool(voteState(),"mayVote")||bool(own,"canDraw")||bool(own,"canSelectTarget");}
    private static String confirmationStep(JsonObject view){JsonObject state=object(view,"state");return str(view,"controlContext")+":"+str(state,"phase")+":"+number(state,"day",0)+":"+number(state,"night",0)+":"+str(object(state,"vote"),"nominee")+":"+object(state,"pendingTask");}
    private boolean assistantUnread(){return assistantInfoStamp!=1&&assistantInfoStamp!=assistantReadStamp;}
    private String gameClock() {
        if(client==null||client.world==null)return "游戏 --:--";
        long time=Math.floorMod(client.world.getTimeOfDay(),24000L);
        return String.format(Locale.ROOT,"%02d:%02d",(time/1000+6)%24,time%1000*60/1000);
    }
    private String trim(String text,int width){return textRenderer.trimToWidth(text,Math.max(8,width));}
    private JsonObject contactMeta(String key){for(JsonElement e:array("contacts"))if(e.isJsonObject()){JsonObject c=e.getAsJsonObject();if(str(c,"id").equals(key)||str(c,"key").equals(key))return c;}return new JsonObject();}
    private int seatColor(String key){JsonObject contact=contactMeta(key);int seat=(int)number(contact,"seat",0);return 0xff000000|(int)number(contact,"color",seat>0?ClockSeats.color(seat):INK);}
    private String seatName(String key,String name){JsonObject c=contactMeta(key);long seat=number(c,"seat",0);return seat>0?seat+"号 · "+name:str(c,"title").isBlank()?name:"说书人 · "+name;}
    private ButtonWidget smallButton(String label,int x,int y,int w,int h,Runnable action) {
        return addDrawableChild(new PhoneButton(label,x,y,Math.max(12,w),h,false,action));
    }
    private TextFieldWidget field(int x,int y,int w,int h,String placeholder,String initial,int max) {
        TextFieldWidget f=new PhoneField(x,y,Math.max(20,w),h,placeholder);f.setMaxLength(max);f.setText(initial);f.setPlaceholder(Text.literal(textRenderer.trimToWidth(placeholder,f.getWidth())));return addDrawableChild(f);
    }
    private JsonArray array(String name){JsonElement e=data.get(name);return e!=null&&e.isJsonArray()?e.getAsJsonArray():new JsonArray();}
    private static JsonArray array(JsonObject value,String key){JsonElement e=value.get(key);return e!=null&&e.isJsonArray()?e.getAsJsonArray():new JsonArray();}
    private static JsonObject object(JsonObject value,String key){JsonElement e=value.get(key);return e!=null&&e.isJsonObject()?e.getAsJsonObject():new JsonObject();}
    private static String str(JsonObject object,String key){try{return TabletopClient.str(object,key);}catch(RuntimeException ex){return "";}}
    private static long number(JsonObject object,String key,long fallback){try{return object.has(key)?object.get(key).getAsLong():fallback;}catch(RuntimeException ex){return fallback;}}
    private static boolean bool(JsonObject object,String key){try{return object.has(key)&&object.get(key).getAsBoolean();}catch(RuntimeException ex){return false;}}

    private void clearPrivateState() {
        received.clear();drafts.clear();readThrough.clear();sending.clear();contacts=List.of();groups=List.of();bubbles=List.of();assistantLines=List.of();
        latestSequence=0;assistantInfoStamp=1;assistantReadStamp=1;conversation="storyteller";contactFilter="";keptAssistantText="";keptGroupTargets="";notice="";waitingAction="";lastFeedbackId=-1;revealedCard="";revealTick=-100;lastRevealSequence=-1;detailReveal=false;cardSelection=0;cardOwner="";confirmAction=null;confirmTarget="";confirmText="";cardIcons.clear();
        selectedTargets.clear();selectedAction=null;voteDataTick=ticks;
        groupsBeforeCreation=Set.of();creatingGroup=false;listScroll=0;chatScroll=0;assistantScroll=0;homeScroll=0;seatChoicePage=0;seatButtons.clear();seatPager=null;backPages.clear();
    }
    @Override public void removed() {
        saveDrafts();
        memory=new Memory(session,viewer,generation,page,conversation,Map.copyOf(drafts));
        if(composeField!=null)composeField.setText("");if(assistantTextField!=null)assistantTextField.setText("");
        if(searchField!=null)searchField.setText("");
        clearPrivateState();data=new JsonObject();super.removed();
    }
    private static void rounded(DrawContext c,int x,int y,int w,int h,int radius,int color) {
        if(w<=0||h<=0)return;int r=Math.min(radius,Math.min(w,h)/2);c.fill(x,y+r,x+w,y+h-r,color);
        for(int row=0;row<r;row++){double dy=r-row-.5;int inset=r-(int)Math.sqrt(r*r-dy*dy);c.fill(x+inset,y+row,x+w-inset,y+row+1,color);c.fill(x+inset,y+h-row-1,x+w-inset,y+h-row,color);}
    }
    /** Native text editing and IME behavior, with a rounded phone surface and inner padding. */
    private final class PhoneField extends TextFieldWidget {
        private final int boxX,boxY,boxWidth,boxHeight;
        PhoneField(int x,int y,int w,int h,String label) {
            super(PhoneScreen.this.textRenderer,x+7,y+(h-8)/2,w-14,8,Text.literal(label));
            boxX=x;boxY=y;boxWidth=w;boxHeight=h;
            setDrawsBackground(false);setEditableColor(INK);setUneditableColor(MUTED);
        }
        @Override public void renderWidget(DrawContext c,int mx,int my,float delta) {
            int radius=Math.min(12,boxHeight/2);
            rounded(c,boxX,boxY,boxWidth,boxHeight,radius,isFocused()?0xff509d9f:0xff38525f);
            rounded(c,boxX+1,boxY+1,boxWidth-2,boxHeight-2,Math.max(1,radius-1),0xff1b303c);
            super.renderWidget(c,mx,my,delta);
        }
        @Override public boolean isMouseOver(double x,double y){return x>=boxX&&x<boxX+boxWidth&&y>=boxY&&y<boxY+boxHeight;}
        @Override public boolean mouseClicked(double x,double y,int button) {
            if(!isMouseOver(x,y))return false;
            return super.mouseClicked(Math.clamp(x,getX(),getX()+getWidth()-.01),getY()+3,button);
        }
    }
    private final class SeatButton extends ButtonWidget {
        private int number;
        SeatButton(int number,int x,int y,int w,int h){
            super(x,y,w,h,Text.literal(number+"号"),button->PhoneScreen.this.chooseSeat(((SeatButton)button).number),DEFAULT_NARRATION_SUPPLIER);this.number=number;
        }
        @Override public boolean isMouseOver(double x,double y){return visible&&y>=listTop&&y<listBottom&&super.isMouseOver(x,y);}
        @Override public boolean mouseClicked(double x,double y,int button){return y>=listTop&&y<listBottom&&super.mouseClicked(x,y,button);}
        @Override protected void renderWidget(DrawContext c,int mx,int my,float delta){
            int x=getX(),y=getY();JsonObject choice=seatChoice(number);boolean current=bool(choice,"current"),highlight=isHovered()||isFocused();
            int color=seatChoiceColor(choice,number),border=current?TEAL:active&&highlight?color:0xff405c6a;
            c.enableScissor(innerLeft,listTop,innerRight,listBottom);
            rounded(c,x,y,width,height,6,border);rounded(c,x+1,y+1,width-2,height-2,5,current?OUTGOING:active&&highlight?0xff294653:SURFACE);
            rounded(c,x+7,y+6,9,9,3,color);
            c.drawText(textRenderer,Text.literal(trim(number+"号 · "+seatChoiceColorName(choice,number),width-25)),x+21,y+6,current||active?INK:MUTED,false);
            if(height>=28){String who=current?"你 · 点击确认":str(choice,"ownerName");if(who.isBlank())who=bool(choice,"available")?"点击选择":"已占用";c.drawText(textRenderer,Text.literal(trim(who,width-14)),x+7,y+(compact()?17:22),current?TEAL:MUTED,false);}
            c.disableScissor();
        }
    }
    private final class PhoneButton extends ButtonWidget {
        private final boolean primary;
        PhoneButton(String label,int x,int y,int w,int h,boolean primary,Runnable action) {
            super(x,y,w,h,Text.literal(label),button->action.run(),DEFAULT_NARRATION_SUPPLIER);this.primary=primary;
        }
        @Override protected void renderWidget(DrawContext c,int mx,int my,float delta) {
            int x=getX(),y=getY(),radius=Math.min(12,height/2);boolean highlight=isHovered()||isFocused();
            int border=primary?(active?TEAL:0xff3f6468):highlight?0xff73b1b3:0xff405c6a;
            int fill=primary?(active?(highlight?0xff72e4da:TEAL):0xff274a50):highlight?0xff294653:0xff213946;
            rounded(c,x,y,width,height,radius,border);rounded(c,x+1,y+1,width-2,height-2,Math.max(1,radius-1),fill);
            String label=trim(getMessage().getString(),width-8);
            c.drawText(textRenderer,Text.literal(label),x+(width-textRenderer.getWidth(label))/2,y+(height-8)/2,primary&&active?0xff102e33:active?INK:MUTED,false);
        }
    }
    private final class NavigationButton extends ButtonWidget {
        private final int icon;
        NavigationButton(String label,int icon,int x,int y,int w,int h,Runnable action) {
            super(x,y,w,h,Text.literal(label),button->action.run(),DEFAULT_NARRATION_SUPPLIER);this.icon=icon;setTooltip(Tooltip.of(Text.literal(label)));
        }
        @Override protected void renderWidget(DrawContext c,int mx,int my,float delta) {
            int x=getX(),y=getY(),cx=x+width/2,cy=y+height/2;boolean highlight=isHovered()||isFocused();
            int surface=highlight?0xff294653:0xff101e28,fg=highlight?TEAL:MUTED;
            if(highlight)rounded(c,x,y,width,height,5,surface);
            switch(icon) {
                case 0 -> {for(int i=0;i<6;i++){c.fill(cx-5+i,cy-i,cx-4+i,cy-i+1,fg);c.fill(cx-5+i,cy+i,cx-4+i,cy+i+1,fg);}c.fill(cx,cy-5,cx+1,cy+6,fg);}
                case 1 -> {rounded(c,cx-6,cy-6,12,12,6,fg);rounded(c,cx-4,cy-4,8,8,4,surface);}
                case 2 -> {c.fill(cx-5,cy-5,cx+5,cy-4,fg);c.fill(cx-5,cy+4,cx+5,cy+5,fg);c.fill(cx-5,cy-5,cx-4,cy+5,fg);c.fill(cx+4,cy-5,cx+5,cy+5,fg);}
                default -> {for(int i=-3;i<=3;i++){c.fill(cx+i,cy+i,cx+i+1,cy+i+1,fg);c.fill(cx+i,cy-i,cx+i+1,cy-i+1,fg);}}
            }
        }
    }
    private final class AppButton extends ButtonWidget {
        final String label,subtitle;final int icon,color;
        AppButton(String label,String subtitle,int icon,int color,Runnable action,int x,int y,int w,int h) {
            super(x,y,w,h,Text.literal(label),button->action.run(),DEFAULT_NARRATION_SUPPLIER);this.label=label;this.subtitle=subtitle;this.icon=icon;this.color=color;
        }
        @Override protected void renderWidget(DrawContext c,int mx,int my,float delta) {
            c.enableScissor(innerLeft,contentTop,innerRight,contentBottom);
            int x=getX(),y=getY();rounded(c,x,y,width,height,9,isHovered()||isFocused()?0xff243e4b:0xff172c38);
            if(compact()){
                int size=14,ix=x+5,iy=y+(height-size)/2;rounded(c,ix,iy,size,size,4,color);
                c.getMatrices().push();c.getMatrices().translate(ix,iy,0);c.getMatrices().scale(size/30f,size/30f,1);drawIcon(c,0,0,30,icon);c.getMatrices().pop();
                c.drawText(textRenderer,Text.literal(trim(label,width-26)),x+23,y+(height-8)/2,active?INK:MUTED,false);
                if((icon==0&&unreadTotal()>0)||(icon==2&&ownPending()))rounded(c,x+width-7,y+3,5,5,2,0xffd66367);
                c.disableScissor();return;
            }
            int size=29,ix=x+(width-size)/2;rounded(c,ix,y+5,size,size,8,color);drawIcon(c,ix,y+5,size,icon);
            String title=trim(label,width-6);c.drawText(textRenderer,Text.literal(title),x+(width-textRenderer.getWidth(title))/2,y+39,INK,false);
            if(height>=64){String small=trim(subtitle,width-6);c.drawText(textRenderer,Text.literal(small),x+(width-textRenderer.getWidth(small))/2,y+53,MUTED,false);}
            if((icon==0&&unreadTotal()>0)||(icon==2&&(ownPending()||assistantUnread()))){rounded(c,ix+24,y+1,10,10,5,0xffd66367);c.drawText(textRenderer,Text.literal("!"),ix+27,y+2,0xffffffff,false);}
            c.disableScissor();
        }
        @Override public boolean mouseClicked(double x,double y,int button){return y>=contentTop&&y<contentBottom&&super.mouseClicked(x,y,button);}
    }
    private static void drawIcon(DrawContext c,int x,int y,int size,int type) {
        int white=0xfff5fafb;
        switch(type) {
            case 0 -> {rounded(c,x+6,y+7,size-12,12,3,white);c.fill(x+7,y+18,x+11,y+22,white);c.fill(x+10,y+11,x+12,y+13,0xff237e84);c.fill(x+17,y+11,x+19,y+13,0xff237e84);}
            case 1 -> {rounded(c,x+11,y+5,8,8,4,white);rounded(c,x+7,y+15,16,10,5,white);}
            case 2 -> {rounded(c,x+6,y+6,18,18,3,white);for(int ox:new int[]{10,18})for(int oy:new int[]{10,18})c.fill(x+ox,y+oy,x+ox+2,y+oy+2,0xff825395);}
            case 3 -> {rounded(c,x+5,y+7,20,15,2,white);rounded(c,x+8,y+10,5,5,2,0xff966642);c.fill(x+15,y+10,x+22,y+12,0xff966642);c.fill(x+15,y+15,x+22,y+17,0xff966642);}
            case 4 -> {rounded(c,x+6,y+5,18,20,2,white);c.fill(x+10,y+5,x+12,y+25,0xff8d3e5a);c.fill(x+15,y+10,x+21,y+12,0xff8d3e5a);}
            default -> {rounded(c,x+7,y+5,16,20,2,white);for(int oy:new int[]{10,15,20})c.fill(x+10,y+oy,x+20,y+oy+1,0xff536c42);}
        }
    }
}
