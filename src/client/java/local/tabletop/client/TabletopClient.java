package local.tabletop.client;

import com.google.gson.*;
import local.tabletop.TabletopNet;
import local.tabletop.TabletopMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import java.util.*;
import org.lwjgl.glfw.GLFW;

/** Physical-prop reading surfaces; no replacement game-board menu. */
public final class TabletopClient implements ClientModInitializer {
    private static long lastGestureResponse = -1;
    private static String controlContext="";
    @Override public void onInitializeClient(){
        NightCurtain.initialize();
        KeyBinding phoneKey=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tabletop.phone",InputUtil.Type.KEYSYM,GLFW.GLFW_KEY_H,"category.tabletop"));
        ClientTickEvents.END_CLIENT_TICK.register(client->{while(phoneKey.wasPressed())if(client.player!=null&&TabletopMod.hasPhone(client.player))send("open","phone","");});
        ClientPlayNetworking.registerGlobalReceiver(TabletopNet.View.ID,(payload,context)->context.client().execute(()->{
            try{
                JsonObject data=JsonParser.parseString(payload.json()).getAsJsonObject();
                if(str(data,"kind").equals("night_vision")){NightCurtain.accept(data);return;}
                if(str(data,"kind").equals("flip_targets_done")){if(context.client().currentScreen instanceof FlipTargetScreen)context.client().setScreen(null);return;}
                if(str(data,"kind").equals("grimoire_locked")){
                    controlContext="";
                    if(context.client().currentScreen instanceof GrimoireScreen||context.client().currentScreen instanceof PropScreen screen&&screen.kind.equals("grimoire_public"))context.client().setScreen(null);
                    return;
                }
                if(data.has("controlContext"))controlContext=str(data,"controlContext");
                if(str(data,"kind").equals("action_feedback")){
                    playAcceptedGesture(context.client(),data);
                    if(context.client().currentScreen instanceof PhoneScreen phone)phone.feedback(data);
                    else if(context.client().currentScreen instanceof GrimoireScreen book)book.feedback(data);
                    else if(context.client().currentScreen instanceof FlipTargetScreen targets)targets.feedback(data);
                    return;
                }
                if(str(data,"kind").equals("closed")){controlContext="";NightCurtain.reset();if(context.client().currentScreen instanceof PropScreen||context.client().currentScreen instanceof PhoneScreen||context.client().currentScreen instanceof GrimoireScreen||context.client().currentScreen instanceof ReferenceScreen||context.client().currentScreen instanceof FlipTargetScreen)context.client().setScreen(null);PhoneScreen.clearMemory();return;}
                if(payload.open()){
                    if(str(data,"kind").equals("flip_targets")){
                        if(context.client().currentScreen instanceof FlipTargetScreen targets)targets.update(data);else context.client().setScreen(new FlipTargetScreen(data));
                    }else if(str(data,"kind").equals("phone")){
                        if(context.client().currentScreen instanceof PhoneScreen phone){phone.update(data);if(data.has("openCards")&&data.get("openCards").getAsBoolean())phone.showCards();}
                        else context.client().setScreen(new PhoneScreen(data));
                    }else if(str(data,"kind").equals("grimoire")||str(data,"kind").equals("clock_review")){
                        if(context.client().currentScreen instanceof GrimoireScreen book)book.update(data);
                        else context.client().setScreen(new GrimoireScreen(data));
                    }else if(ReferenceScreen.supports(str(data,"kind"))){
                        if(context.client().currentScreen instanceof ReferenceScreen reference)reference.update(data);
                        else context.client().setScreen(new ReferenceScreen(data));
                    }else if(context.client().currentScreen instanceof PropScreen screen&&screen.kind.equals(str(data,"kind")))screen.update(data);
                    else context.client().setScreen(new PropScreen(data));
                    if(context.client().player!=null)context.client().player.playSound(SoundEvents.UI_BUTTON_CLICK.value(),.3f,1.2f);
                }else if(str(data,"kind").equals("flip_targets")&&context.client().currentScreen instanceof FlipTargetScreen targets)targets.update(data);
                else if(str(data,"kind").equals("phone")&&context.client().currentScreen instanceof PhoneScreen phone)phone.update(data);
                else if((str(data,"kind").equals("grimoire")||str(data,"kind").equals("clock_review"))&&context.client().currentScreen instanceof GrimoireScreen book)book.update(data);
                else if(ReferenceScreen.supports(str(data,"kind"))&&context.client().currentScreen instanceof ReferenceScreen reference)reference.update(data);
                else if(context.client().currentScreen instanceof PropScreen screen&&screen.kind.equals(str(data,"kind")))screen.update(data);
            }catch(RuntimeException ignored){}
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->client.execute(()->{controlContext="";lastGestureResponse=-1;NightCurtain.reset();if(client.currentScreen instanceof PropScreen||client.currentScreen instanceof PhoneScreen||client.currentScreen instanceof GrimoireScreen||client.currentScreen instanceof ReferenceScreen||client.currentScreen instanceof FlipTargetScreen)client.setScreen(null);PhoneScreen.clearMemory();}));
    }
    /** Animate only acknowledged decisions; swingHand also informs other players. */
    private static void playAcceptedGesture(MinecraftClient client,JsonObject feedback){
        if(client.player==null||!feedback.has("ok")||!feedback.get("ok").getAsBoolean())return;
        String action=str(feedback,"action");
        if(!action.equals("draw")&&!action.equals("stay"))return;
        long response=Long.parseLong(str(feedback,"requestId"));
        if(response<=lastGestureResponse)return;
        lastGestureResponse=response;
        client.player.swingHand(action.equals("draw")?Hand.MAIN_HAND:Hand.OFF_HAND);
        client.player.playSound(SoundEvents.ITEM_BOOK_PAGE_TURN,.35f,action.equals("draw")?1.15f:.75f);
    }
    static String str(JsonObject object,String key){var value=object.get(key);return value==null||value.isJsonNull()?"":value.getAsString();}
    static void send(String action,String target,String text){sendControl(action,target,text,controlContext);}
    static void sendControl(String action,String target,String text,String context){if(ClientPlayNetworking.canSend(TabletopNet.Action.ID))ClientPlayNetworking.send(new TabletopNet.Action(action,target,text,context));}
    static final class PropScreen extends Screen {
        JsonObject data;final String kind;int left,top,panelWidth,panelHeight,scroll,actionPage;boolean controls;
        TextFieldWidget targetField,messageField;List<OrderedText> lines=List.of();String keptTarget="storyteller",keptMessage="";int ticks,contactIndex,roleIndex;
        PropScreen(JsonObject data){super(Text.literal(str(data,"title")));this.data=data;kind=str(data,"kind");controls=kind.equals("phone")||kind.equals("grimoire");if(kind.equals("grimoire"))keptTarget="";}
        void update(JsonObject data){this.data=data;wrap();if(controls&&targetField!=null)rebuildActions();}
        @Override protected void init(){
            panelWidth=Math.min(width-20,kind.equals("phone")?330:kind.equals("grimoire")?570:390);panelHeight=Math.min(height-18,460);left=(width-panelWidth)/2;top=(height-panelHeight)/2;
            clearChildren();int bottom=top+panelHeight;
            addDrawableChild(ButtonWidget.builder(Text.literal("×"),b->close()).dimensions(left+panelWidth-25,top+7,18,18).build());
            if(controls){
                targetField=new TextFieldWidget(textRenderer,left+14,bottom-100,panelWidth-85,18,Text.literal("收件人或目标"));targetField.setMaxLength(160);targetField.setText(keptTarget);targetField.setPlaceholder(Text.literal(kind.equals("phone")?"收件人 / 目标玩家，用逗号分隔":"目标玩家名"));addDrawableChild(targetField);
                addDrawableChild(ButtonWidget.builder(Text.literal("选玩家"),b->{var contacts=data.getAsJsonArray("contacts");if(contacts!=null&&!contacts.isEmpty()){var contact=contacts.get(Math.floorMod(contactIndex++,contacts.size())).getAsJsonObject();targetField.setText(str(contact,"key"));}}).dimensions(left+panelWidth-66,bottom-100,52,18).build());
                messageField=new TextFieldWidget(textRenderer,left+14,bottom-78,panelWidth-(kind.equals("grimoire")?85:28),18,Text.literal("消息或裁定"));messageField.setMaxLength(600);messageField.setText(keptMessage);messageField.setPlaceholder(Text.literal(kind.equals("phone")?"私密消息":"消息 / 角色id / 裁定信息 / 状态 true"));addDrawableChild(messageField);
                if(kind.equals("grimoire"))addDrawableChild(ButtonWidget.builder(Text.literal("选角色"),b->{var roles=data.getAsJsonArray("roleChoices");if(roles!=null&&!roles.isEmpty()){var role=roles.get(Math.floorMod(roleIndex++,roles.size())).getAsJsonObject();messageField.setText(str(role,"id"));}}).dimensions(left+panelWidth-66,bottom-78,52,18).build());
                if(kind.equals("phone"))addDrawableChild(ButtonWidget.builder(Text.literal("发送"),b->{send("message",targetField.getText(),messageField.getText());messageField.setText("");}).dimensions(left+14,bottom-54,58,18).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("操作"),b->{remember();actionPage++;rebuildActions();}).dimensions(left+panelWidth-72,bottom-54,58,18).build());
                rebuildActions();
            }
            if(kind.equals("grimoire_public")){
                var buttons=data.getAsJsonArray("buttons");
                if(buttons!=null)for(int i=0;i<Math.min(2,buttons.size());i++){
                    var spec=buttons.get(i).getAsJsonObject();
                    int bw=(panelWidth-34)/2;
                    addDrawableChild(new ActionButton(Text.literal(str(spec,"label")),b->send(str(spec,"action"),str(spec,"target"),str(spec,"text")),left+14+i*(bw+6),bottom-31,bw,18));
                }
            }
            wrap();
        }
        void remember(){if(targetField!=null)keptTarget=targetField.getText();if(messageField!=null)keptMessage=messageField.getText();}
        void rebuildActions(){
            var buttons=data.getAsJsonArray("buttons");if(buttons==null)return;
            // Bottom two action buttons page through server-authorized choices without overflowing small screens.
            removeActionButtons();int count=buttons.size();if(count==0)return;int pageCount=(count+1)/2;actionPage=Math.floorMod(actionPage,pageCount);
            int available=panelWidth-28;int bw=(available-6)/2;
            for(int i=0;i<2;i++){int index=actionPage*2+i;if(index>=count)break;var spec=buttons.get(index).getAsJsonObject();var button=new ActionButton(Text.literal(str(spec,"label")),b->{String target=str(spec,"target"),text=str(spec,"text");if(target.equals("$target"))target=targetField.getText();if(text.equals("$text"))text=messageField.getText();String action=str(spec,"action");send(action,target,text);if(Set.of("assign","start","resolve","mark","phones").contains(action)){messageField.setText("");keptMessage="";}},left+14+i*(bw+6),top+panelHeight-30,bw,18);addDrawableChild(button);}
        }
        void removeActionButtons(){var copy=new ArrayList<>(children());for(var child:copy)if(child instanceof ActionButton button)remove(button);}
        void wrap(){
            if(textRenderer==null)return;var output=new ArrayList<OrderedText>();var body=data.getAsJsonArray("body");if(body!=null)for(var row:body){String value=row.getAsString();if(kind.equals("identity_card")&&value.startsWith("身份："))continue;for(String paragraph:value.split("\n")){output.addAll(textRenderer.wrapLines(Text.literal(paragraph),Math.max(60,panelWidth-42)));output.add(Text.literal("").asOrderedText());}}lines=output;scroll=Math.min(scroll,maxScroll());
        }
        String identityRole(){var body=data.getAsJsonArray("body");if(body!=null)for(var row:body)if(row.isJsonPrimitive()&&row.getAsString().startsWith("身份："))return row.getAsString().substring(3);return "等待发牌";}
        int readingTop(){return kind.equals("identity_card")?97:43;}
        int viewHeight(){return panelHeight-(controls?157:kind.equals("identity_card")?128:kind.equals("grimoire_public")?94:74);}
        int maxScroll(){return Math.max(0,lines.size()*11-viewHeight());}
        @Override public void tick(){if(controls&&++ticks%60==0)send("refresh","","");}
        @Override public boolean shouldPause(){return false;}
        @Override public void renderBackground(DrawContext c,int mx,int my,float delta){}
        @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){scroll=Math.clamp(scroll-(int)(vertical*28),0,maxScroll());return true;}
        @Override public boolean keyPressed(int code,int scan,int modifiers){if(code==257&&kind.equals("phone")&&messageField!=null&&messageField.isFocused()){send("message",targetField.getText(),messageField.getText());messageField.setText("");return true;}return super.keyPressed(code,scan,modifiers);}
        @Override public void resize(MinecraftClient client,int width,int height){remember();super.resize(client,width,height);}
        @Override public void render(DrawContext c,int mx,int my,float delta){
            // Keep the Minecraft room visible; only the physical phone/card/book reading area is drawn.
            boolean phone=kind.equals("phone"),book=kind.equals("grimoire");int background=phone?0xff101b26:0xffeee0bd,ink=phone?0xffdae7ed:0xff3b2b27,accent=phone?0xff55c9d7:0xff9b2836;
            c.fill(left-3,top-3,left+panelWidth+3,top+panelHeight+3,phone?0xff080d12:0xff32201c);
            c.fill(left,top,left+panelWidth,top+panelHeight,background);
            c.fill(left+7,top+7,left+panelWidth-7,top+37,phone?0xff1d3441:0xffdec99b);
            if(phone){c.fill(left+panelWidth/2-22,top+3,left+panelWidth/2+22,top+6,0xff05080c);c.fill(left+panelWidth/2-20,top+panelHeight-5,left+panelWidth/2+20,top+panelHeight-3,0xff506672);}
            if(book){for(int n=0;n<3;n++)c.fill(left+panelWidth-6+n,top+10+n,left+panelWidth-5+n,top+panelHeight-8-n,0xffbbaa85);c.fill(left+8,top+38,left+11,top+panelHeight-12,0xffb89966);}
            c.drawText(textRenderer,Text.literal(str(data,"title")),left+14,top+17,accent,false);
            if(kind.equals("identity_card")){
                String roleName=identityRole();var role=local.tabletop.game.RoleCatalog.all().stream().filter(r->r.name().equals(roleName)).findFirst().orElse(null);
                String itemId=role==null?"identity_card":"role_"+role.id();var item=TabletopMod.ITEMS.get(itemId);
                c.fill(left+12,top+44,left+panelWidth-12,top+90,0xffe1cd9f);
                if(item!=null){c.getMatrices().push();c.getMatrices().translate(left+18,top+48,0);c.getMatrices().scale(2f,2f,1f);c.drawItem(item.getDefaultStack(),0,0);c.getMatrices().pop();}
                c.drawText(textRenderer,Text.literal(textRenderer.trimToWidth(roleName,panelWidth-80)),left+58,top+52,accent,false);
                String detail=role==null?"说书人确认开局后查看":(List.of("minion","demon").contains(role.type())?"邪恶阵营":"善良阵营")+" · 仅本人的身份";
                c.drawText(textRenderer,Text.literal(textRenderer.trimToWidth(detail,panelWidth-80)),left+58,top+70,0xff756044,false);
            }
            int readingY=top+readingTop();c.enableScissor(left+12,readingY,left+panelWidth-12,readingY+viewHeight());
            for(int i=0;i<lines.size();i++){int y=readingY+3+i*11-scroll;if(y>=readingY-10&&y<readingY+viewHeight())c.drawText(textRenderer,lines.get(i),left+18,y,ink,false);}
            c.disableScissor();
            if(maxScroll()>0){int thumb=Math.max(12,viewHeight()*viewHeight()/(lines.size()*11));int y=readingY+(viewHeight()-thumb)*scroll/Math.max(1,maxScroll());c.fill(left+panelWidth-9,y,left+panelWidth-6,y+thumb,accent);}
            c.drawText(textRenderer,Text.literal(controls?"滚轮阅读 · 操作按钮翻页":"滚轮阅读 · Esc收起"),left+14,top+panelHeight-(controls?116:21),phone?0xff79939d:0xff897452,false);
            super.render(c,mx,my,delta);
        }
        @Override public void removed(){data=new JsonObject();lines=List.of();keptTarget="";keptMessage="";if(messageField!=null)messageField.setText("");super.removed();}
    }
    static final class ActionButton extends ButtonWidget {
        ActionButton(Text label,PressAction action,int x,int y,int width,int height){super(x,y,width,height,label,action,DEFAULT_NARRATION_SUPPLIER);}
    }
}
