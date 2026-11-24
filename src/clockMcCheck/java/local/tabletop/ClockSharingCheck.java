package local.tabletop;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Uses real Minecraft server/world/player classes, in a disposable world only. */
public final class ClockSharingCheck implements ModInitializer {
    public static final UUID LAN_OWNER=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final BlockPos ANCHOR=new BlockPos(-899,172,839), LECTERN=new BlockPos(-903,172,839);
    private final List<String> passed=new ArrayList<>();
    private final List<String> menuChecks=new ArrayList<>();

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            String failure="";
            try { run(server); } catch(Throwable error) { error.printStackTrace(); failure=error.toString(); }
            try {
                Files.writeString(Path.of("clock-sharing-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status",failure.isEmpty()?"passed":"failed","checks",passed,"menuChecks",menuChecks,"failure",failure,"scope","Real isolated Minecraft server, original arena fixtures, simulated player connections and LAN owner.")));
            } catch(Exception error) { throw new RuntimeException(error); }
            System.out.println("CLOCK_SHARING_CHECK_"+(failure.isEmpty()?"OK":"FAILED")+": "+passed.size());
            server.stop(false);
        });
    }

    private void check(boolean condition,String name) {
        if(!condition)throw new AssertionError(name);
        passed.add(name);
    }
    private void rejects(Runnable action,String name) {
        boolean rejected=false;
        try { action.run(); } catch(IllegalStateException|IllegalArgumentException expected) { rejected=true; }
        check(rejected,name);
    }
    private FakePlayer player(MinecraftServer server,ServerWorld world,UUID id,String name,boolean op) {
        FakePlayer p=new FakePlayer(server,world,new GameProfile(id,name),op);
        p.refreshPositionAndAngles(ANCHOR.getX()+.5,ANCHOR.getY(),ANCHOR.getZ()+.5,0,0);
        p.networkHandler=new ServerPlayNetworkHandler(server,p.connection,p,new ConnectedClientData(p.getGameProfile(),0,p.getClientOptions(),false));
        p.networkHandler.onCustomPayload(new CustomPayloadC2SPacket(new RegistrationPayload(RegistrationPayload.REGISTER,List.of(TabletopNet.View.ID.id()))));
        if(name.startsWith("Rules"))ok(ServerPlayNetworking.canSend(p,TabletopNet.View.ID),name+": menu channel registered");else check(ServerPlayNetworking.canSend(p,TabletopNet.View.ID),name+": phone channel registered");
        p.getInventory().setStack(0,TabletopMod.ITEMS.get("phone").getDefaultStack());
        return p;
    }

    private void run(MinecraftServer server)throws Exception {
        Path folder=server.getSavePath(WorldSavePath.ROOT).resolve("tabletop");Files.createDirectories(folder);
        Path bindingFile=folder.resolve("fixed-clock.json"),arenaFile=folder.resolve("clock-arenas.json");
        Files.copy(Path.of("fixtures/fixed-clock.json"),bindingFile,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("fixtures/clock-arenas.json"),arenaFile,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        byte[] original=Files.readAllBytes(bindingFile),arena=Files.readAllBytes(arenaFile);
        FixedClockTable.clearCache();
        var fixed=FixedClockTable.get(server);
        UUID oldOwner = fixed.hostUUID();
        check(!oldOwner.equals(LAN_OWNER)&&fixed.arenaAnchor().equals(ANCHOR)&&fixed.grimoirePos().equals(LECTERN),"Original shared-save fixture loaded");
        FakePlayer ordinary=player(server,server.getOverworld(),UUID.randomUUID(),"Guest",false);
        FakePlayer operator=player(server,server.getOverworld(),UUID.randomUUID(),"Operator",true);
        FakePlayer owner=player(server,server.getOverworld(),LAN_OWNER,"NewLanOwner",false);
        FakePlayer originalOwner=player(server,server.getOverworld(),oldOwner,"OriginalOwner",false);
        check(FixedClockTable.canCreate(originalOwner,fixed),"Original storyteller remains allowed");
        check(!FixedClockTable.canCreate(ordinary,fixed),"Ordinary guest cannot take over");
        rejects(()->FixedClockTable.forCreation(ordinary,LECTERN),"Guest creation rejected server-side");
        Sessions.openGrimoireBlock(ordinary,LECTERN);
        check(Sessions.GAMES.isEmpty()&&Arrays.equals(original,Files.readAllBytes(bindingFile)),"Guest lectern click cannot alter old binding");
        Sessions.open(ordinary,"phone",true);
        JsonObject guestPhone=ordinary.connection.last("phone");
        check(hasButton(guestPhone,"create","clock","false"),"Guest phone visibly retains disabled Clocktower entry");
        Sessions.action(ordinary,new TabletopNet.Action("create","clock",""));
        check(Sessions.GAMES.isEmpty()&&Arrays.equals(original,Files.readAllBytes(bindingFile)),"Forged guest create packet cannot take over");
        check(FixedClockTable.canCreate(operator,fixed),"Operator may host an unopened shared save");
        check(!owner.hasPermissionLevel(2)&&server.isHost(owner.getGameProfile())&&FixedClockTable.canCreate(owner,fixed),"LAN owner identity works without cheat permission (simulated host)");
        owner.refreshPositionAndAngles(0,172,0,0,0);
        rejects(()->FixedClockTable.forCreation(owner,LECTERN),"New host must be within 64 blocks");
        check("false".equals(Sessions.clockLobbyButton(owner).get("enabled")),"Distant host sees location guidance");
        owner.refreshPositionAndAngles(ANCHOR.getX()+.5,ANCHOR.getY(),ANCHOR.getZ()+.5,0,0);
        FakePlayer wrongDimension=player(server,server.getWorld(World.NETHER),UUID.randomUUID(),"OtherDimension",true);
        rejects(()->FixedClockTable.forCreation(wrongDimension,LECTERN),"Operator cannot create in the wrong dimension");
        rejects(()->FixedClockTable.checkLectern(owner,LECTERN.add(1,0,0)),"Takeover cannot move the fixed lectern");
        if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("megawalls")) {
            var switchTo=Class.forName("com.megawalls.minigame.GameSessions").getMethod("switchTo",ServerPlayerEntity.class,String.class);
            if(!Boolean.TRUE.equals(switchTo.invoke(null,owner,"clocktower")))throw new AssertionError("Cannot prepare Clocktower destination inventory");
        }
        for(int i=0;i<owner.getInventory().size();i++)owner.getInventory().setStack(i,new ItemStack(Items.STONE,64));
        check(Sessions.command(owner,"create clock")==0&&Arrays.equals(original,Files.readAllBytes(bindingFile)),"Full inventory fails without changing ownership");
        owner.getInventory().clear();owner.getInventory().setStack(0,TabletopMod.ITEMS.get("phone").getDefaultStack());
        Sessions.open(owner,"phone",true);
        check(hasButton(owner.connection.last("phone"),"create","clock","true"),"New host phone offers takeover");
        Sessions.openGrimoireBlock(owner,LECTERN);
        check(Sessions.GAMES.size()==1&&Sessions.current(owner).host.equals(LAN_OWNER),"New host opens the fixed lectern successfully");
        check(owner.connection.last("grimoire").getAsJsonObject("state").has("grimoire"),"New host receives storyteller grimoire");
        check(FixedClockTable.get(server).hostUUID().equals(LAN_OWNER),"New ownership persisted");
        check(Arrays.equals(original,Files.readAllBytes(folder.resolve("fixed-clock.before-host-change-"+oldOwner+".json"))),"Original binding backed up before takeover");
        check(Arrays.equals(arena,Files.readAllBytes(arenaFile))&&FixedClockTable.get(server).arenaAnchor().equals(ANCHOR)&&FixedClockTable.get(server).grimoirePos().equals(LECTERN),"All venue coordinates and room configuration preserved");
        FixedClockTable.clearCache();check(FixedClockTable.get(server).hostUUID().equals(LAN_OWNER),"New host binding survives cache reload");
        rejects(()->FixedClockTable.forCreation(operator,LECTERN),"Another operator cannot replace an open table");
        Sessions.open(ordinary,"phone",true);
        String id=Sessions.current(owner).id;
        check(hasButton(ordinary.connection.last("phone"),"join",id,null),"Guest phone offers joining the opened Clocktower table");
        Sessions.openGrimoireBlock(ordinary,LECTERN);
        check(!ordinary.connection.last("grimoire_public").getAsJsonObject("state").has("grimoire"),"Guest lectern view exposes public seats only");
        Sessions.action(ordinary,new TabletopNet.Action("join",id,""));
        check("clock".equals(Sessions.gameOf(ordinary))&&Sessions.current(owner).roster.containsKey(ordinary.getUuid()),"Guest joins through the actual phone action");
        check(!ordinary.connection.last("phone").getAsJsonObject("state").has("grimoire"),"Player phone does not expose private grimoire");
        Sessions.open(ordinary,"grimoire",true);
        check(ordinary.connection.last("grimoire_locked")!=null,"Guest handheld grimoire stays locked");
        var session=Sessions.current(owner);session.running=true;
        Sessions.openGrimoireBlock(operator,LECTERN);
        check(operator.connection.last("grimoire_locked")!=null&&session.host.equals(LAN_OWNER),"Even operators cannot read another active game's grimoire");
        check("false".equals(Sessions.clockLobbyButton(operator).get("enabled")),"Ongoing game remains visible but not joinable");
        session.running=false;
        check(Sessions.command(owner,"close")==1,"Host can close normally");
        check(FixedClockTable.get(server).hostUUID().equals(LAN_OWNER),"Closing preserves fixed venue ownership");
        Sessions.action(operator,new TabletopNet.Action("create","clock",""));
        check(Sessions.current(operator).host.equals(operator.getUuid()),"Operator takeover through phone create succeeds after close");
        check(Arrays.equals(arena,Files.readAllBytes(arenaFile)),"Phone takeover also preserves room layout");
        Sessions.command(operator,"close");
        if(Boolean.getBoolean("tabletop.check.settings"))rulesTest(server);
    }

    private void ok(boolean b,String label){if(!b)throw new AssertionError(label);menuChecks.add(label);}
    private int items(FakePlayer p){int n=0;for(int i=0;i<p.getInventory().size();i++)if(p.getInventory().getStack(i).isOf(GameRulesMenu.item))n++;return n;}
    private String encoded(String g,JsonObject vals){var j=new java.util.StringJoiner(",");for(var r:GameRuleList.LISTS.get(g))j.add(vals.get(r.key()).getAsString());return j.toString();}
    private JsonObject click(FakePlayer p){for(int i=0;i<9;i++)if(p.getInventory().getStack(i).isOf(GameRulesMenu.item)){p.getInventory().selectedSlot=i;
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker().interact(p,p.getWorld(),net.minecraft.util.Hand.MAIN_HAND);return p.connection.last("game_settings");}throw new AssertionError("No settings in hotbar");}
    private void rulesTest(MinecraftServer server)throws Exception{
        var change=Class.forName("com.megawalls.minigame.GameSessions").getMethod("switchTo",ServerPlayerEntity.class,String.class);
        Path root=server.getSavePath(WorldSavePath.ROOT);int idx=0;
        for(String g:List.of("flip7","bedwars","hot_potato","tnt_run")){
            boolean flip=g.equals("flip7");FakePlayer p=player(server,server.getOverworld(),UUID.randomUUID(),"Rules"+(++idx),false);
            ok(Boolean.TRUE.equals(change.invoke(null,p,g))&&GameRulesMenu.game(p).equals(g),g+": ordinary player entered real hub profile");
            var inv=p.getInventory();int fullSlot=-1;for(int i=0;i<36;i++)if(inv.getStack(i).isEmpty()){inv.setStack(i,Items.COBBLESTONE.getDefaultStack());if(fullSlot<0)fullSlot=i;}
            GameRulesMenu.sync(p);ok(fullSlot>=0&&items(p)==0&&inv.getStack(fullSlot).isOf(Items.COBBLESTONE),g+": full inventory not overwritten");inv.setStack(fullSlot,ItemStack.EMPTY);
            GameRulesMenu.sync(p);GameRulesMenu.sync(p);ok(items(p)==1,g+": exactly one item granted after freeing slot");
            for(int i=0;i<36;i++)if(inv.getStack(i).isOf(Items.COBBLESTONE))inv.setStack(i,ItemStack.EMPTY);
            JsonObject view=click(p),vals=view.getAsJsonObject("values").deepCopy();ok(view.get("game").getAsString().equals(g)&&view.get("editable").getAsBoolean()&&vals.size()==GameRuleList.LISTS.get(g).size(),g+": real right-click opens full live menu");
            String key=switch(g){case "flip7"->"goal";case "bedwars"->"respawnSeconds";case "hot_potato"->"round1";default->"delayTicks";};int edit=switch(g){case "flip7"->78;case "bedwars"->9;case "hot_potato"->125;default->16;};
            if(g.equals("hot_potato")||g.equals("tnt_run"))ok(vals.get("maxPlayers").getAsInt()==16,g+": original 16-player arena setting shown");
            vals.addProperty(key,99999);GameRulesMenu.apply(p,view.get("ticket").getAsString(),encoded(g,vals));view=p.connection.last("game_settings");ok(!view.get("saved").getAsBoolean()&&view.getAsJsonObject("values").get(key).getAsInt()!=99999,g+": invalid value rejected");vals.addProperty(key,edit);
            switch(g){case "flip7"->{vals.addProperty("bonus",31);vals.addProperty("auto",1);vals.addProperty("delay",2);vals.addProperty("max",6);}case "bedwars"->vals.addProperty("ironTicks",113);case "hot_potato"->{vals.addProperty("passCooldownTicks",27);vals.addProperty("assistRevealOthers",0);}default->vals.addProperty("graceTicks",9);}
            Path file=root.resolve(switch(g){case "flip7"->"tabletop/flip-settings.json";case "bedwars"->"bedwars.json";case "hot_potato"->"hot-potato.json";default->"tnt-run.json";});JsonObject before=flip?null:JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            FakePlayer second=player(server,server.getOverworld(),UUID.randomUUID(),"RulesGuest"+idx,false);change.invoke(null,second,g);GameRulesMenu.sync(second);var earlier=click(second);
            Sessions.action(p,new TabletopNet.Action("rules_save",view.get("ticket").getAsString(),encoded(g,vals)));view=p.connection.last("game_settings");ok(view.get("saved").getAsBoolean()&&view.getAsJsonObject("values").equals(vals),g+": normal player's save packet applied; "+view.get("message").getAsString());
            GameRulesMenu.apply(second,earlier.get("ticket").getAsString(),encoded(g,earlier.getAsJsonObject("values")));var conflict=second.connection.last("game_settings");ok(!conflict.get("saved").getAsBoolean()&&conflict.getAsJsonObject("values").equals(vals),g+": old concurrent draft rejected, latest values returned");
            JsonObject disk=JsonParser.parseString(Files.readString(file)).getAsJsonObject();ok((g.equals("hot_potato")?disk.getAsJsonArray("roundSeconds").get(0):disk.get(key)).getAsInt()==edit,g+": original game config file changed on disk");
            if(before!=null){for(String name:before.keySet())if(!vals.has(name)&&!name.equals("roundSeconds")&&!java.util.Objects.equals(before.get(name),disk.get(name)))throw new AssertionError(g+": changed unrelated field "+name);ok(true,g+": arena geometry and all non-rule fields preserved");}
            else{var rules=FlipOptions.get(server).rules();ok(rules.goal()==78&&rules.bonus()==31&&rules.autoNext()&&rules.delayTicks()==40,g+": saved options supplied to game engine");}
            view=click(p);ok(view.getAsJsonObject("values").equals(vals),g+": reopening shows saved values");
            if(!flip){String n=switch(g){case "bedwars"->"BedWars";case "hot_potato"->"HotPotato";default->"TntRun";};var sf=Class.forName("com.megawalls.minigame."+n).getDeclaredField("stage");sf.setAccessible(true);Object prev=sf.get(null);
                try{for(var v:sf.getType().getEnumConstants())if(((Enum<?>)v).name().equals("RUNNING"))sf.set(null,v);GameRulesMenu.apply(p,view.get("ticket").getAsString(),encoded(g,vals));ok(!p.connection.last("game_settings").get("editable").getAsBoolean(),g+": match in progress rejects edits");GameRulesMenu.sync(p);ok(items(p)==0,g+": settings removed in match");}finally{sf.set(null,prev);}}
            else{Sessions.action(p,new TabletopNet.Action("create","flip",""));var table=Sessions.current(p);table.running=true;try{GameRulesMenu.apply(p,view.get("ticket").getAsString(),encoded(g,vals));ok(!p.connection.last("game_settings").get("editable").getAsBoolean(),g+": active card table rejects edits");GameRulesMenu.sync(p);ok(items(p)==0,g+": no settings during card play");}finally{table.running=false;Sessions.command(p,"close");}}
            GameRulesMenu.sync(second);var last=click(second);change.invoke(null,second,"hub");GameRulesMenu.sync(second);GameRulesMenu.apply(second,last.get("ticket").getAsString(),encoded(g,vals));ok(GameRulesMenu.game(second).isEmpty()&&items(second)==0,g+": departing player no longer has settings");
        }
    }

    private static boolean hasButton(JsonObject phone,String action,String target,String enabled) {
        for(var e:phone.getAsJsonArray("buttons")){
            JsonObject b=e.getAsJsonObject();
            if(action.equals(b.get("action").getAsString())&&target.equals(b.get("target").getAsString())&&(enabled==null||enabled.equals(b.get("enabled").getAsString())))return true;
        }
        return false;
    }
    private static final class FakePlayer extends ServerPlayerEntity {
        final RecordingConnection connection=new RecordingConnection();private final boolean op;
        FakePlayer(MinecraftServer server,ServerWorld world,GameProfile profile,boolean op){super(server,world,profile,SyncedClientOptions.createDefault());this.op=op;}
        @Override public boolean hasPermissionLevel(int level){return level<=0||op&&level<=2;}
    }
    private static final class RecordingConnection extends ClientConnection {
        private final List<JsonObject> views=new ArrayList<>();
        RecordingConnection(){super(NetworkSide.SERVERBOUND);}
        private void record(Packet<?> packet){if(packet instanceof CustomPayloadS2CPacket custom&&custom.payload() instanceof TabletopNet.View view)views.add(JsonParser.parseString(view.json()).getAsJsonObject());}
        JsonObject last(String kind){for(int i=views.size()-1;i>=0;i--){var view=views.get(i);if(view.has("kind")&&kind.equals(view.get("kind").getAsString()))return view;}throw new AssertionError("No view: "+kind);}
        @Override public void send(Packet<?> p){record(p);}
        @Override public void send(Packet<?> p,PacketCallbacks c){record(p);}
        @Override public void send(Packet<?> p,PacketCallbacks c,boolean flush){record(p);}
        @Override public boolean isOpen(){return true;}
        @Override public boolean isLocal(){return false;}
        @Override public void flush(){}
        @Override public void tryDisableAutoRead(){}
        @Override public SocketAddress getAddress(){return new InetSocketAddress("127.0.0.1",0);}
    }
}
