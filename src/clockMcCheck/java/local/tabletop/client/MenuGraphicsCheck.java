package local.tabletop.client;

import com.google.gson.Gson;
import local.tabletop.TabletopMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import java.nio.file.*;
import java.util.*;

/** Drives only the local disposable integrated test game, using ordinary screen widgets and play packets. */
public final class MenuGraphicsCheck implements ClientModInitializer {
    private static final String[] IDS={"flip7","bedwars","hot_potato","tnt_run","classic_pvp","megawalls"}, NAMES={"七连翻","起床战争","烫手山芋","TNT Run","UHC PvP"}, VAL={"77","17","124","13","3"};
    private int i,step,tick,last;private boolean done;private volatile String fail="";private final List<String> checks=new ArrayList<>();
    private static ButtonWidget button(net.minecraft.client.gui.screen.Screen s,String n){return s.children().stream().filter(w->w instanceof ButtonWidget b&&b.getMessage().getString().contains(n)).map(w->(ButtonWidget)w).findFirst().orElseThrow();}
    private static TextFieldWidget field(net.minecraft.client.gui.screen.Screen s,int idx){return s.children().stream().filter(w->w instanceof TextFieldWidget).map(w->(TextFieldWidget)w).skip(idx).findFirst().orElseThrow();}
    private static void screen(MinecraftClient c,String cls)throws Exception{var t=net.minecraft.client.gui.screen.Screen.class;c.setScreen((net.minecraft.client.gui.screen.Screen)Class.forName(cls).getConstructor(t).newInstance(new Object[]{null}));}
    private void advance(){step++;last=tick;}
    private void finish(MinecraftClient c,String f){if(done)return;done=true;try{Files.writeString(Path.of("menu-graphics.json"),new Gson().toJson(Map.of("passed",f.isEmpty()&&checks.size()>=23,"checks",checks,"failure",f,"game",i,"stage",step)));}catch(Exception x){x.printStackTrace();}c.scheduleStop();}
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(c->{if(done)return;tick++;try{
        if(!fail.isEmpty())throw new AssertionError(fail);if(tick-last>1500)throw new AssertionError("timed out: "+(c.currentScreen==null?"world":c.currentScreen.getClass().getName()));
        if(c.player==null||c.world==null||c.getServer()==null||c.interactionManager==null||tick-last<12)return;
        String g=IDS[i];int idx=0;
        if(step==0){if(c.currentScreen!=null)return;var server=c.getServer();var uuid=c.player.getUuid();server.execute(()->{try{var pl=server.getPlayerManager().getPlayer(uuid);if(!Boolean.TRUE.equals(Class.forName("com.megawalls.minigame.GameSessions").getMethod("switchTo",ServerPlayerEntity.class,String.class).invoke(null,pl,g)))fail="could not enter "+g;}catch(Throwable e){fail=e.toString();}});advance();return;}
        if(i==5){var current=c.currentScreen;var fx=Class.forName("com.megawalls.maintenance.client.ClientEffectTuning");
            if(step==1){screen(c,"com.megawalls.client.screen.MWSettingsScreen");advance();return;}
            if(step==2){button(current,"关闭").onPress();if((boolean)fx.getMethod("enabled").invoke(null))throw new AssertionError("off failed");checks.add("MW: effects turned off by native menu");advance();return;}
            if(step==3){ScreenshotRecorder.saveScreenshot(c.runDirectory,"settings-mw_fx.png",c.getFramebuffer(),m->{});button(current,"精简").onPress();if(!"精简".equals(fx.getMethod("name").invoke(null)))throw new AssertionError("simplified failed");checks.add("MW: simplified mode selected");screen(c,"com.megawalls.maintenance.client.MatchTuningScreen");advance();return;}
            if(step==4){var loaded=current.getClass().getDeclaredField("loaded");loaded.setAccessible(true);if(!loaded.getBoolean(current))return;button(current,"守卫技能").onPress();field(current,0).setText("9");button(current,"保存全部").onPress();advance();return;}
            if(step==5){var m=current.getClass().getDeclaredField("message");m.setAccessible(true);if(!m.get(current).toString().contains("已保存"))return;ScreenshotRecorder.saveScreenshot(c.runDirectory,"settings-mw_core.png",c.getFramebuffer(),t->{});checks.add("MW: new guard damage saved over network, painted menu captured");finish(c,"");}return;}
        if(step==1||step==4){int pos=-1;for(int h=0;h<9;h++){var st=c.player.getInventory().getStack(h);if(st.isOf(TabletopMod.ITEMS.get("game_settings"))&&st.getName().getString().contains(NAMES[i])){pos=h;break;}}if(pos<0)return;
            c.player.getInventory().selectedSlot=pos;c.interactionManager.interactItem(c.player,Hand.MAIN_HAND);advance();return;}
        if(!(c.currentScreen instanceof GameRulesScreen s)||!s.getTitle().getString().startsWith(NAMES[i]))return;
        if(step==2){checks.add(g+": right-click opened correct on-screen menu");if(i==1)button(s,"凋零弓").onPress();var f=field(s,idx);if(f.getText().equals(VAL[i]))throw new AssertionError("fixture already modified: "+g);
            if(i==2){button(s,"下一页").onPress();button(s,"上一页").onPress();}f=field(s,idx);f.setText(VAL[i]);if(!button(s,"保存设置").active)throw new AssertionError("uneditable");button(s,"保存设置").onPress();advance();return;}
        if(step==3){var ok=GameRulesScreen.class.getDeclaredField("good");ok.setAccessible(true);if(!ok.getBoolean(s))return;checks.add(g+": actual server confirmed widget edit");ScreenshotRecorder.saveScreenshot(c.runDirectory,"settings-"+g+".png",c.getFramebuffer(),m->{});checks.add(g+": saved screen captured");s.close();advance();return;}
        if(step==5){if(i==1)button(s,"凋零弓").onPress();if(!field(s,idx).getText().equals(VAL[i]))throw new AssertionError("lost saved value: "+g);checks.add(g+": reopening displays saved value "+VAL[i]);s.close();i++;step=0;last=tick;}
    }catch(Throwable e){e.printStackTrace();finish(c,e.toString());}});}
}
