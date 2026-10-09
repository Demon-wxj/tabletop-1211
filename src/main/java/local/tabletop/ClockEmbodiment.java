package local.tabletop;

import local.tabletop.game.ClockGame;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.block.DoorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import java.nio.file.*;
import java.util.*;

/** The Minecraft stage projects, rather than replaces, the Storyteller's rule decisions. */
public final class ClockEmbodiment {
    private static final String TAG="tabletop_clock_scene";
    private static final Set<String> SCENE_PROPS=Set.of("phone","identity_card","grimoire","storyteller_wand");
    private static final Map<String,Scene> SCENES=new LinkedHashMap<>();
    private static final Set<UUID> AUTHORIZED_DEATH=new HashSet<>();
    private static final Map<UUID,Death> DEATHS=new HashMap<>();
    private static final Set<UUID> RESTORING=new HashSet<>();
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("tabletop-clock-stage");
    private static final class Scene {
        final Sessions.Session session;
        final MinecraftServer server;
        final ClockArena.Layout layout;
        final String arenaKey;
        final Map<UUID,NbtCompound> before=new LinkedHashMap<>();
        final Map<UUID,NbtCompound> costumes=new HashMap<>();
        final Set<UUID> seenNightDead=new HashSet<>(),seenExecuted=new HashSet<>(),seenLogicalDead=new HashSet<>();
        String phase="",taskKey="";
        String approvedKey="";
        String approvedInfo="";
        UUID approvedTarget;
        int nextImpSound;
        Scene(Sessions.Session s,MinecraftServer server,ClockArena.Layout layout){session=s;this.server=server;this.layout=layout;arenaKey=ClockArena.key(s);}
        ServerWorld world(){return server.getWorld(session.dimension);}
    }
    private static final class Death {
        final Scene scene;
        final UUID player;
        final boolean execution;
        int at;
        NbtCompound before;
        ServerPlayerEntity corpse;
        Death(Scene scene,UUID player,boolean execution,int at){this.scene=scene;this.player=player;this.execution=execution;this.at=at;}
    }
    private ClockEmbodiment(){}
    private static Scene scene(Sessions.Session s){Scene value=SCENES.get(s.id);return value!=null&&value.session==s?value:null;}
    private static Scene scene(ServerPlayerEntity p){Sessions.Session s=Sessions.GAMES.get(Sessions.MEMBERS.get(p.getUuid()));return s==null?null:scene(s);}
    public static boolean active(ServerPlayerEntity p){return scene(p)!=null;}
    public static boolean disguised(UUID id){return SCENES.values().stream().anyMatch(s->s.costumes.containsKey(id));}
    public static String lockIssue(Sessions.Session s,MinecraftServer server){
        ClockArena.Layout layout=ClockArena.get(s,server);if(layout==null||!layout.enabled)return "";
        for(Scene other:SCENES.values())if(other.server==server&&other.session!=s&&other.session.dimension.equals(s.dimension)){
            if(other.arenaKey.equals(ClockArena.key(s)))return "这处场地已有对局，请先结束或关闭该桌。";
            if(layout.center!=null&&other.layout.center.block().equals(layout.center.block()))return "中间场地已有另一桌对局。";
            for(var room:layout.rooms.values())if(room.bounds!=null)for(var occupied:other.layout.rooms.values())if(occupied.bounds!=null&&room.bounds.overlaps(occupied.bounds))return "玩家房间与另一桌进行中的场地重叠。";
        }
        return inventoryIssue(s,server);
    }
    public static String inventoryIssue(Sessions.Session s,MinecraftServer server){
        for(UUID id:s.audience()){
            var player=server.getPlayerManager().getPlayer(id);
            if(player==null||!player.isAlive())return "所有本桌成员需要在线且存活。";
            if(!player.currentScreenHandler.getCursorStack().isEmpty())return player.getGameProfile().getName()+" 请先把鼠标上的物品放回背包。";
            for(int i=1;i<=4;i++)if(!player.playerScreenHandler.getSlot(i).getStack().isEmpty())return player.getGameProfile().getName()+" 请先收回合成栏里的物品。";
            int reusable=0,required=s.host.equals(id)?3:4;
            for(int i=0;i<36;i++){ItemStack item=player.getInventory().getStack(i);if(item.isEmpty()||managed(item)||!ordinaryPropKind(item).isEmpty())reusable++;}
            if(reusable<required)return player.getGameProfile().getName()+" 请先腾出 "+(required-reusable)+" 格背包空间。";
        }
        return "";
    }
    private static Path recovery(ServerPlayerEntity p){return p.getServer().getSavePath(WorldSavePath.ROOT).resolve("tabletop/clock-recovery").resolve(p.getUuidAsString()+".dat");}
    private static void write(Path path,NbtCompound n){
        try{Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");NbtIo.writeCompressed(n,temp);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        catch(Exception e){throw new IllegalStateException("场景玩家档案保存失败，尚未开始移动或换装。",e);}
    }
    private static NbtCompound capture(ServerPlayerEntity p){
        NbtCompound n=new NbtCompound();n.put("inventory",p.getInventory().writeNbt(new NbtList()));n.putInt("slot",p.getInventory().selectedSlot);n.putInt("mode",p.interactionManager.getGameMode().getId());n.putString("team",TabletopPresentation.originalTeam(p));n.putInt("captureTick",p.getServer().getTicks());
        n.putFloat("health",p.getHealth());n.putFloat("absorption",p.getAbsorptionAmount());n.putDouble("max",p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH).getBaseValue());p.getHungerManager().writeNbt(n);
        n.putInt("xpTotal",p.totalExperience);n.putInt("xpLevel",p.experienceLevel);n.putFloat("xpProgress",p.experienceProgress);
        NbtList effects=new NbtList();for(var effect:p.getStatusEffects())effects.add(effect.writeNbt());n.put("effects",effects);
        n.putBoolean("invisible",p.isInvisible());n.putBoolean("glowing",p.isGlowingLocal());n.putBoolean("invulnerable",p.isInvulnerable());
        n.putBoolean("allowFlying",p.getAbilities().allowFlying);n.putBoolean("flying",p.getAbilities().flying);n.putFloat("flySpeed",p.getAbilities().getFlySpeed());
        n.putString("dimension",p.getWorld().getRegistryKey().getValue().toString());n.putDouble("x",p.getX());n.putDouble("y",p.getY());n.putDouble("z",p.getZ());n.putFloat("yaw",p.getYaw());n.putFloat("pitch",p.getPitch());return n;
    }
    private static void restore(ServerPlayerEntity p,NbtCompound n,boolean location){
        RESTORING.add(p.getUuid());try{
            p.closeHandledScreen();p.stopRiding();p.stopUsingItem();p.getInventory().clear();p.getInventory().readNbt(n.getList("inventory",NbtElement.COMPOUND_TYPE));
            p.getInventory().selectedSlot=Math.clamp(n.getInt("slot"),0,8);p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket(p.getInventory().selectedSlot));
            p.changeGameMode(GameMode.byId(n.getInt("mode")));p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(n.getDouble("max"));p.clearStatusEffects();
            int elapsed=Math.max(0,p.getServer().getTicks()-n.getInt("captureTick"));for(var entry:n.getList("effects",NbtElement.COMPOUND_TYPE)){var effect=StatusEffectInstance.fromNbt((NbtCompound)entry);if(effect!=null){int left=effect.getDuration()<0?-1:effect.getDuration()-elapsed;if(left>0||left<0&&effect.getDuration()<0)p.addStatusEffect(new StatusEffectInstance(effect.getEffectType(),left,effect.getAmplifier(),effect.isAmbient(),effect.shouldShowParticles(),effect.shouldShowIcon()));}}
            p.setHealth(Math.max(1,Math.min(p.getMaxHealth(),n.getFloat("health"))));p.setAbsorptionAmount(n.getFloat("absorption"));p.getHungerManager().readNbt(n);
            p.totalExperience=n.getInt("xpTotal");p.experienceLevel=n.getInt("xpLevel");p.experienceProgress=n.getFloat("xpProgress");p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket(p.experienceProgress,p.totalExperience,p.experienceLevel));
            p.setInvisible(n.getBoolean("invisible"));p.setGlowing(n.getBoolean("glowing"));p.setInvulnerable(n.getBoolean("invulnerable"));p.getAbilities().allowFlying=n.getBoolean("allowFlying");p.getAbilities().flying=n.getBoolean("flying")&&p.getAbilities().allowFlying;p.getAbilities().setFlySpeed(n.getFloat("flySpeed"));p.sendAbilitiesUpdate();
            p.extinguish();p.fallDistance=0;p.setVelocity(net.minecraft.util.math.Vec3d.ZERO);p.currentScreenHandler.syncState();
            if(location){var world=p.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(n.getString("dimension"))));if(world!=null)new ClockArena.Point(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"),n.getFloat("yaw"),n.getFloat("pitch")).teleport(p,world);}
        }finally{RESTORING.remove(p.getUuid());}
    }
    public static void start(Sessions.Session s,MinecraftServer server){
        if(!ClockArena.enabled(s,server))return;if(scene(s)!=null)return;String issue=ClockArena.issue(s,server);if(!issue.isEmpty())throw new IllegalStateException(issue);issue=lockIssue(s,server);if(!issue.isEmpty())throw new IllegalStateException(issue);
        Scene stage=new Scene(s,server,ClockArena.get(s,server));
        for(UUID id:s.audience()){
            var player=server.getPlayerManager().getPlayer(id);
            if(player==null||!player.isAlive())throw new IllegalStateException("所有本桌成员需要在线且存活。");
            if(Files.exists(recovery(player)))recover(player);
        }
        issue=inventoryIssue(s,server);if(!issue.isEmpty())throw new IllegalStateException(issue);
        for(UUID id:s.audience()){ServerPlayerEntity player=server.getPlayerManager().getPlayer(id);if(player==null||!player.isAlive())throw new IllegalStateException("所有本桌成员需要在线且存活。");if(Files.exists(recovery(player)))recover(player);NbtCompound n=capture(player);write(recovery(player),n);stage.before.put(id,n);}
        SCENES.put(s.id,stage);ClockRoomDoors.prepare(s,stage.layout,server);for(UUID id:s.roster.keySet()){var player=server.getPlayerManager().getPlayer(id);player.changeGameMode(GameMode.ADVENTURE);player.setHealth(player.getMaxHealth());player.getHungerManager().setFoodLevel(20);player.getHungerManager().setSaturationLevel(5);}
        sync(s,server);
    }
    private static ClockArena.Room room(Scene scene,UUID id){int seat=TabletopPresentation.seat(scene.session,id);return scene.layout.rooms.get(seat);}
    private static void home(Scene stage,ServerPlayerEntity p){var room=room(stage,p.getUuid());if(room!=null&&stage.world()!=null)room.spawn.teleport(p,stage.world());}
    private static void seat(Scene stage,ServerPlayerEntity p){var point=stage.session.host.equals(p.getUuid())?stage.layout.center:stage.layout.seats.get(TabletopPresentation.seat(stage.session,p.getUuid()));if(point!=null&&stage.world()!=null)point.teleport(p,stage.world());}
    private static void closeDoors(Scene stage){
        if(stage.world()==null)return;
        for(var room:stage.layout.rooms.values())if(room.door!=null)ClockRoomDoors.setOpen(stage.world(),room.door,false);
    }
    private static void impSound(Scene stage){
        var task=stage.session.clock.physicalTask();
        if(!stage.session.clock.isNight()||!stage.session.offline.isEmpty()||task==null||!task.role().equals("imp")||!stage.costumes.containsKey(task.actor()))return;
        if(stage.server.getTicks()<stage.nextImpSound)return;
        stage.nextImpSound=stage.server.getTicks()+80;
        // A common cue avoids exposing the actor's identity or current target by sound position.
        for(UUID id:stage.session.audience()){
            var p=stage.server.getPlayerManager().getPlayer(id);
            if(p!=null)p.playSoundToPlayer(net.minecraft.sound.SoundEvents.ENTITY_WITHER_AMBIENT,net.minecraft.sound.SoundCategory.HOSTILE,.65f,.9f);
        }
    }
    private static String dayKey(Scene stage){return "day:"+stage.session.clock.publicView().get("day");}
    private static boolean dayVote(Scene stage,UUID id){if(stage.session.clock.isAlive(id))return true;Object rows=stage.session.clock.publicView().get("seats");if(rows instanceof List<?> seats)for(Object row:seats)if(row instanceof Map<?,?> value&&id.toString().equals(value.get("uuid")))return Boolean.TRUE.equals(value.get("ghostVote"));return false;}
    private static Set<String> expectedProps(Scene stage,UUID id){Set<String> result=new LinkedHashSet<>();result.add("phone");if(stage.session.host.equals(id)){result.add("grimoire");result.add("storyteller_wand");}else if(stage.session.roster.containsKey(id)){result.add("identity_card");if(stage.session.clock!=null&&stage.session.clock.phase().equals("day")){if(Boolean.TRUE.equals(stage.session.clock.privateView(id).get("canNominate")))result.add("nominate");if(dayVote(stage,id))result.add("vote");}}return result;}
    private static String ordinaryPropKind(ItemStack item){if(item.isEmpty())return "";for(String kind:SCENE_PROPS)if(item.isOf(TabletopMod.ITEMS.get(kind)))return kind;if(item.isOf(TabletopMod.ITEMS.get("nomination_token")))return "nominate";if(item.isOf(TabletopMod.ITEMS.get("vote_token")))return "vote";return "";}
    private static void restoreCostume(Scene stage,UUID id){NbtCompound n=stage.costumes.remove(id);var player=stage.server.getPlayerManager().getPlayer(id);if(n!=null&&player!=null&&player.isAlive())restore(player,n,false);}
    private static NbtCompound data(ItemStack stack){return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getCompound(TAG);}
    public static boolean managed(ItemStack stack){return !stack.isEmpty()&&stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().contains(TAG);}
    private static ItemStack prop(Scene stage,UUID owner,ClockGame.PhysicalTask task,String kind){return prop(stage,owner,task.key(),kind);}
    private static ItemStack prop(Scene stage,UUID owner,String key,String kind){
        ItemStack item=new ItemStack(switch(kind){case "knife"->Items.IRON_SWORD;case "phone","identity_card","grimoire","storyteller_wand"->TabletopMod.ITEMS.get(kind);case "nominate"->TabletopMod.ITEMS.get("nomination_token");case "vote"->TabletopMod.ITEMS.get("vote_token");case "armor0"->Items.NETHERITE_BOOTS;case "armor1"->Items.NETHERITE_LEGGINGS;case "armor2"->Items.NETHERITE_CHESTPLATE;case "armor3"->Items.WITHER_SKELETON_SKULL;default->TabletopMod.ITEMS.get("role_imp");});NbtCompound meta=new NbtCompound();meta.putString("session",stage.session.id);meta.putInt("generation",stage.session.generation);meta.putString("owner",owner.toString());meta.putString("task",key);meta.putString("kind",kind);
        NbtCompound n=new NbtCompound();n.put(TAG,meta);n.putBoolean("mg_bound",true);n.putString("mg_owner",owner.toString());item.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(n));item.set(DataComponentTypes.MAX_STACK_SIZE,1);item.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(false));if(Set.of("knife","self","nominate","vote","identity_card").contains(kind))item.set(DataComponentTypes.CUSTOM_NAME,Text.literal(switch(kind){case "knife"->"夜刃";case "self"->"自杀";case "nominate"->"提名";case "identity_card"->"身份卡";default->"投票";}).styled(st->st.withItalic(false)));return item;
    }
    private static void costume(Scene stage,ServerPlayerEntity p,ClockGame.PhysicalTask task){
        if(stage.costumes.containsKey(p.getUuid()))return;stage.costumes.put(p.getUuid(),capture(p));p.closeHandledScreen();p.stopUsingItem();p.getInventory().clear();p.getInventory().setStack(0,prop(stage,p.getUuid(),task,"knife"));p.getInventory().setStack(1,prop(stage,p.getUuid(),task,"self"));p.getInventory().setStack(2,prop(stage,p.getUuid(),"scene","phone"));p.getInventory().setStack(3,prop(stage,p.getUuid(),"scene","identity_card"));
        for(int i=0;i<4;i++)p.getInventory().armor.set(i,prop(stage,p.getUuid(),task,"armor"+i));
        p.setInvisible(true);p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY,600,0,true,false,false));p.setGlowing(false);p.getInventory().selectedSlot=0;p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket(0));p.currentScreenHandler.syncState();if(stage.world()!=null)stage.layout.center.teleport(p,stage.world());TabletopMessages.night(p,"右键铁门申请 · 说书人确认后开门");
    }
    public static void sync(Sessions.Session s,MinecraftServer server){
        Scene stage=scene(s);if(stage==null||s.clock==null)return;if(s.clock.finished()){release(s,server);return;}
        for(Death death:new ArrayList<>(DEATHS.values()))if(death.scene==stage&&s.clock.isAlive(death.player)){
            var player=server.getPlayerManager().getPlayer(death.player);
            if(death.corpse==null){DEATHS.remove(death.player,death);if(player!=null&&player.isAlive()){if(s.clock.isNight())home(stage,player);else seat(stage,player);}}
            else if(player!=null)respawn(death);
        }
        boolean night=s.clock.isNight();String phase=s.clock.phase()+":"+s.clock.privateView(s.host).get("night")+":"+s.clock.privateView(s.host).get("day");
        if(!stage.phase.equals(phase)){
            closeDoors(stage);
            for(UUID id:new HashSet<>(stage.costumes.keySet()))restoreCostume(stage,id);stage.approvedKey="";stage.approvedInfo="";stage.approvedTarget=null;stage.taskKey="";stage.phase=phase;
            if(night)stage.seenNightDead.clear();
            for(UUID id:s.audience()){var p=server.getPlayerManager().getPlayer(id);if(p==null||!p.isAlive()||DEATHS.containsKey(id))continue;if(night&&!s.host.equals(id))home(stage,p);else seat(stage,p);}
        }
        ClockGame.PhysicalTask task=s.clock.physicalTask();String key=task==null?"":task.key();
        if(!key.equals(stage.taskKey)){
            closeDoors(stage);stage.nextImpSound=0;
            for(UUID id:new HashSet<>(stage.costumes.keySet())){restoreCostume(stage,id);var p=server.getPlayerManager().getPlayer(id);if(p!=null&&p.isAlive()&&night)home(stage,p);}
            stage.taskKey=key;stage.approvedKey="";stage.approvedInfo="";stage.approvedTarget=null;
        }
        if(night&&task!=null&&task.role().equals("imp")&&!s.offline.contains(task.actor())){var p=server.getPlayerManager().getPlayer(task.actor());if(p!=null&&p.isAlive()&&!DEATHS.containsKey(task.actor()))costume(stage,p,task);}
        stage.seenExecuted.retainAll(s.clock.executedDeathIds());stage.seenNightDead.retainAll(s.clock.nightDeathIds());
        for(UUID id:s.clock.executedDeathIds())if(stage.seenExecuted.add(id))scheduleDeath(stage,id,true);
        for(UUID id:s.clock.nightDeathIds())if(stage.seenNightDead.add(id)&&!stage.seenExecuted.contains(id))scheduleDeath(stage,id,false);
        Set<UUID> logicalDead=new HashSet<>();for(UUID id:s.roster.keySet())if(!s.clock.isAlive(id))logicalDead.add(id);stage.seenLogicalDead.retainAll(logicalDead);for(UUID id:logicalDead)if(stage.seenLogicalDead.add(id)&&!stage.seenExecuted.contains(id)&&!stage.seenNightDead.contains(id))scheduleDeath(stage,id,false);
        for(UUID id:s.audience()){var player=server.getPlayerManager().getPlayer(id);if(player!=null&&player.isAlive())syncInventory(player);}
    }
    private static void scheduleDeath(Scene stage,UUID id,boolean execution){
        if(DEATHS.containsKey(id))return;restoreCostume(stage,id);var player=stage.server.getPlayerManager().getPlayer(id);Death death=new Death(stage,id,execution,stage.server.getTicks()+(execution?20:1));DEATHS.put(id,death);
        if(player!=null&&player.isAlive()){if(execution)stage.layout.jail.teleport(player,stage.world());else if(stage.session.clock.isNight())home(stage,player);else seat(stage,player);}
    }
    public static boolean allowsDamage(ServerPlayerEntity p){return !active(p)||AUTHORIZED_DEATH.contains(p.getUuid());}
    public static boolean silentDeath(ServerPlayerEntity p){return AUTHORIZED_DEATH.contains(p.getUuid())&&DEATHS.containsKey(p.getUuid());}
    public static void onSilentDeath(ServerPlayerEntity p){
        Death death=DEATHS.get(p.getUuid());if(death==null)return;death.corpse=p;death.at=p.getServer().getTicks()+1;
        if(death.execution)p.getServerWorld().sendEntityStatus(p,(byte)3);else deathAnimation(p);p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket(0,p.getHungerManager().getFoodLevel(),p.getHungerManager().getSaturationLevel()));
    }
    private static void deathAnimation(ServerPlayerEntity player){
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket(player,(byte)3));
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket(player.getId(),0));
    }
    private static void executeDeath(Death death,ServerPlayerEntity player){
        death.before=capture(player);AUTHORIZED_DEATH.add(player.getUuid());try{player.setInvulnerable(false);player.setHealth(0);player.onDeath(player.getDamageSources().genericKill());}
        finally{AUTHORIZED_DEATH.remove(player.getUuid());}if(death.corpse==null){death.corpse=player;death.at=death.scene.server.getTicks()+1;}
    }
    private static void respawn(Death death){
        ServerPlayerEntity old=death.corpse;if(old==null)return;var server=death.scene.server;ServerPlayerEntity current=server.getPlayerManager().getPlayer(death.player);if(current==null)return;
        ServerPlayerEntity player=current==old?server.getPlayerManager().respawnPlayer(old,false,Entity.RemovalReason.KILLED):current;player.networkHandler.player=player;player.networkHandler.syncWithPlayerPosition();restore(player,death.before,false);player.setHealth(player.getMaxHealth());player.setAbsorptionAmount(0);player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket(player.getHealth(),player.getHungerManager().getFoodLevel(),player.getHungerManager().getSaturationLevel()));
        if(death.scene.session.clock!=null&&death.scene.session.clock.isNight())home(death.scene,player);else seat(death.scene,player);DEATHS.remove(death.player);Sessions.refresh(death.scene.session,server);
    }
    public static void tick(MinecraftServer server){
        for(Scene stage:new ArrayList<>(SCENES.values()))if(stage.server==server){sync(stage.session,server);if(scene(stage.session)==null)continue;impSound(stage);
            for(UUID id:stage.session.roster.keySet()){var player=server.getPlayerManager().getPlayer(id);if(player==null||!player.isAlive()||DEATHS.containsKey(id))continue;player.getHungerManager().setFoodLevel(20);player.getHungerManager().setSaturationLevel(5);
                if(stage.session.clock!=null&&stage.session.clock.isNight()){
                    ClockGame.PhysicalTask task=stage.session.clock.physicalTask();boolean imp=task!=null&&task.role().equals("imp")&&task.actor().equals(id)&&stage.costumes.containsKey(id);
                    var own=room(stage,id);if(imp){
                        if(!player.getWorld().getRegistryKey().equals(stage.session.dimension))home(stage,player);
                        for(var entry:stage.layout.rooms.entrySet())if(entry.getValue().bounds!=null&&entry.getValue().bounds.contains(player.getX(),player.getY(),player.getZ())){UUID owner=Sessions.clockPlayerAt(stage.session,entry.getKey());if(owner==null||!task.key().equals(stage.approvedKey)||!Objects.equals(stage.approvedTarget,owner)){home(stage,player);break;}}
                    }else if(own!=null&&(player.getServerWorld()!=stage.world()||!own.bounds.contains(player.getX(),player.getY(),player.getZ())))home(stage,player);
                }
            }
        }
        for(Death death:new ArrayList<>(DEATHS.values()))if(death.scene.server==server&&server.getTicks()>=death.at){var player=server.getPlayerManager().getPlayer(death.player);if(player==null)continue;if(death.corpse==null)executeDeath(death,player);else respawn(death);}
    }
    private static boolean currentProp(Scene stage,ServerPlayerEntity p,ItemStack item,String kind){if(!managed(item))return false;NbtCompound meta=data(item);if(!meta.getString("session").equals(stage.session.id)||meta.getInt("generation")!=stage.session.generation||!meta.getString("owner").equals(p.getUuidAsString())||!meta.getString("kind").equals(kind))return false;if(SCENE_PROPS.contains(kind))return meta.getString("task").equals("scene")&&(kind.equals("phone")||kind.equals("identity_card")&&stage.session.roster.containsKey(p.getUuid())||Set.of("grimoire","storyteller_wand").contains(kind)&&stage.session.host.equals(p.getUuid()));if(Set.of("nominate","vote").contains(kind))return stage.session.clock!=null&&expectedProps(stage,p.getUuid()).contains(kind)&&meta.getString("task").equals(dayKey(stage));var task=stage.session.clock==null?null:stage.session.clock.physicalTask();return task!=null&&stage.session.clock.isNight()&&task.role().equals("imp")&&task.actor().equals(p.getUuid())&&stage.costumes.containsKey(p.getUuid())&&meta.getString("task").equals(task.key());}
    private static int propSlot(Scene stage,ServerPlayerEntity p){
        int empty=p.getInventory().getEmptySlot();if(empty>=0)return empty;
        NbtCompound saved=stage.before.get(p.getUuid());if(saved==null)return -1;
        var original=new net.minecraft.entity.player.PlayerInventory(p);original.readNbt(saved.getList("inventory",NbtElement.COMPOUND_TYPE));
        // Reclaim a match's original prop/empty slot without creating a dropped entity.
        // Original possessions remain in the profile restored when the scene closes.
        for(int i=35;i>=0;i--)if(!managed(p.getInventory().getStack(i))&&(original.getStack(i).isEmpty()||managed(original.getStack(i))||!ordinaryPropKind(original.getStack(i)).isEmpty()))return i;
        return -1;
    }
    public static void syncInventory(ServerPlayerEntity p){
        if(RESTORING.contains(p.getUuid()))return;
        Scene stage=scene(p);
        if(stage!=null&&stage.costumes.containsKey(p.getUuid())){var task=stage.session.clock==null?null:stage.session.clock.physicalTask();if(task==null||!stage.session.clock.isNight()||!task.role().equals("imp")||!task.actor().equals(p.getUuid())||!task.key().equals(stage.taskKey)){restoreCostume(stage,p.getUuid());if(stage.session.clock!=null&&stage.session.clock.isNight()&&p.isAlive())home(stage,p);}}
        Set<String> expectedKinds=stage==null?Set.of():expectedProps(stage,p.getUuid()),seen=new HashSet<>();boolean changed=false;var inv=p.getInventory();
        for(int i=0;i<inv.size();i++){
            ItemStack item=inv.getStack(i);
            if(stage!=null&&!managed(item)){
                String kind=ordinaryPropKind(item);
                if(!kind.isEmpty()){inv.setStack(i,i<36&&expectedKinds.contains(kind)?prop(stage,p.getUuid(),SCENE_PROPS.contains(kind)?"scene":dayKey(stage),kind):ItemStack.EMPTY);item=inv.getStack(i);changed=true;}
            }
            if(!managed(item))continue;
            String kind=data(item).getString("kind");boolean correctSlot=(i<36&&(SCENE_PROPS.contains(kind)||Set.of("knife","self","nominate","vote").contains(kind)))||(i>=36&&i<=39&&kind.equals("armor"+(i-36)));
            if(stage==null||!correctSlot||!currentProp(stage,p,item,kind)||item.getCount()!=1||!seen.add(kind)){inv.setStack(i,ItemStack.EMPTY);changed=true;}
        }
        var handler=p.currentScreenHandler;if(managed(handler.getCursorStack())){handler.setCursorStack(ItemStack.EMPTY);changed=true;}for(var slot:handler.slots)if(slot.inventory!=inv&&managed(slot.getStack())){slot.setStack(ItemStack.EMPTY);changed=true;}
        if(stage!=null&&stage.costumes.containsKey(p.getUuid())){ClockGame.PhysicalTask task=stage.session.clock.physicalTask();if(task!=null){for(String kind:List.of("knife","self"))if(!seen.contains(kind)){int empty=inv.getEmptySlot();if(empty>=0){inv.setStack(empty,prop(stage,p.getUuid(),task,kind));changed=true;}}}
            for(int i=0;i<4;i++){ItemStack expected=prop(stage,p.getUuid(),task,"armor"+i);if(!ItemStack.areItemsAndComponentsEqual(inv.armor.get(i),expected)){inv.armor.set(i,expected);changed=true;}}p.setInvisible(true);p.setGlowing(false);var invisible=p.getStatusEffect(StatusEffects.INVISIBILITY);if(invisible==null||invisible.getDuration()<40)p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY,80,0,true,false,false));}
        if(stage!=null)for(String kind:expectedKinds)if(!seen.contains(kind)){int empty=propSlot(stage,p);if(empty>=0){inv.setStack(empty,prop(stage,p.getUuid(),SCENE_PROPS.contains(kind)?"scene":dayKey(stage),kind));changed=true;}}
        if(changed){inv.markDirty();handler.syncState();}
    }
    private static void request(Scene stage,ServerPlayerEntity p,UUID target){
        if(!stage.session.offline.isEmpty())throw new IllegalStateException("本桌有人离线，等待重连。");ClockGame.PhysicalTask task=stage.session.clock.physicalTask();if(task==null||!task.role().equals("imp")||!task.actor().equals(p.getUuid()))throw new IllegalStateException("当前不是你的行动。");
        if(task.submitted())throw new IllegalStateException("目标已选择，等待说书人。" );String result=stage.session.clock.submit(p.getUuid(),List.of(target));if(!ClockGame.acceptedResponse("submit",result))throw new IllegalStateException(result);TabletopMessages.night(p,"已请求本次行动 · 等待说书人确认");Sessions.refresh(stage.session,stage.server);
    }
    public static void use(ServerPlayerEntity p,Hand hand){Scene stage=scene(p);ItemStack stack=p.getStackInHand(hand);if(stage==null)return;if(currentProp(stage,p,stack,"nominate")){Sessions.openNomination(p);return;}if(currentProp(stage,p,stack,"vote")){Sessions.openVoting(p);return;}if(currentProp(stage,p,stack,"phone")){Sessions.open(p,"phone",true);return;}if(currentProp(stage,p,stack,"identity_card")){Sessions.open(p,"identity_card",true);return;}if(currentProp(stage,p,stack,"grimoire")||currentProp(stage,p,stack,"storyteller_wand")){Sessions.open(p,"grimoire",true);return;}if(!currentProp(stage,p,stack,"self"))return;try{request(stage,p,p.getUuid());}catch(RuntimeException e){TabletopMessages.warning(p,e.getMessage());}}
    private static ActionResult doorUse(ServerPlayerEntity p,BlockPos pos){
        Scene stage=scene(p);if(stage==null)return ActionResult.PASS;Sessions.Session s=stage.session;if(s.clock==null||!s.clock.isNight())return ActionResult.PASS;if(s.host.equals(p.getUuid()))return ActionResult.PASS;
        for(var entry:stage.layout.rooms.entrySet())if(entry.getValue().door!=null&&entry.getValue().door.matches(pos)){
            ClockGame.PhysicalTask task=s.clock.physicalTask();if(task==null||!task.role().equals("imp")||!task.actor().equals(p.getUuid()))return ActionResult.FAIL;
            UUID target=Sessions.clockPlayerAt(s,entry.getKey());if(target==null)return ActionResult.FAIL;
            try{
                if(!task.submitted()){request(stage,p,target);return ActionResult.SUCCESS;}
                if(!task.targets().equals(List.of(target)))throw new IllegalStateException("本次目标已经锁定。" );
                if(!stage.approvedKey.equals(task.key())||!Objects.equals(stage.approvedTarget,target))throw new IllegalStateException("等待说书人确认。" );
                if(!s.clock.impEntryPossible(p.getUuid(),target)){String result=s.clock.resolve(s.host,stage.approvedInfo);if(!ClockGame.acceptedResponse("resolve",result))throw new IllegalStateException(result);deathAnimation(p);Sessions.refresh(s,stage.server);return ActionResult.SUCCESS;}
                TabletopMessages.night(p,"房门已开 · 进入后用夜刃攻击目标");return ActionResult.SUCCESS;
            }catch(RuntimeException e){TabletopMessages.warning(p,e.getMessage());return ActionResult.FAIL;}
        }
        return ActionResult.FAIL;
    }
    public static String resolve(Sessions.Session s,ServerPlayerEntity storyteller,String info){
        Scene stage=scene(s);if(stage==null||s.clock==null)return null;Sessions.host(s,storyteller.getUuid());ClockGame.PhysicalTask task=s.clock.physicalTask();if(task==null||!task.role().equals("imp"))return null;if(!task.submitted())throw new IllegalStateException("等待小恶魔选择本次目标。" );
        UUID target=task.targets().getFirst();boolean healthyMayor=s.clock.trueRole(target).equals("mayor")&&!s.clock.impairedPlayer(target);boolean possible=s.clock.impEntryPossible(task.actor(),target);boolean ordinary=!target.equals(task.actor())&&possible&&(!healthyMayor||info.equals("default"));
        if(stage.approvedKey.equals(task.key())&&possible)throw new IllegalStateException("已允许进入，等待夜刃命中；或取消本次行动。" );
        if(ordinary){if(!info.isBlank()&&!info.equals("default"))throw new IllegalStateException("本次选择需要先允许进入，再以夜刃完成。" );closeDoors(stage);if(!ClockRoomDoors.setOpen(stage.world(),room(stage,target).door,true))throw new IllegalStateException("房门异常，请联系说书人。");stage.approvedKey=task.key();stage.approvedInfo=healthyMayor?"default":"";stage.approvedTarget=target;var player=stage.server.getPlayerManager().getPlayer(task.actor());if(player!=null)TabletopMessages.night(player,"房门已开 · 进入后用夜刃攻击目标");return "房门已打开，等待夜刃命中。";}
        boolean animation=!possible||target.equals(task.actor());String result=info.isBlank()?s.clock.resolve(storyteller.getUuid()):s.clock.resolve(storyteller.getUuid(),info);if(!ClockGame.acceptedResponse("resolve",result))throw new IllegalStateException(result);if(animation){var actor=stage.server.getPlayerManager().getPlayer(task.actor());if(actor!=null){deathAnimation(actor);}}sync(s,stage.server);return result;
    }
    public static String decide(Sessions.Session s,ServerPlayerEntity storyteller,String key,UUID selected){Scene stage=scene(s);if(stage==null||s.clock==null)return null;var task=s.clock.physicalTask();if(task==null||!task.role().equals("imp"))return null;Sessions.host(s,storyteller.getUuid());if(key.endsWith(":mayor_default")){if(s.clock.decisionChoices(storyteller.getUuid()).stream().noneMatch(choice->choice.get("id").equals(key)))throw new IllegalStateException("裁定已过期。" );return resolve(s,storyteller,"default");}String result=s.clock.decide(storyteller.getUuid(),key,selected);if(!ClockGame.acceptedResponse("resolve",result))throw new IllegalStateException(result);sync(s,stage.server);return result;}
    public static String cancel(Sessions.Session s,ServerPlayerEntity storyteller,String key){Scene stage=scene(s);if(stage==null||s.clock==null)throw new IllegalStateException("当前没有场景行动。" );Sessions.host(s,storyteller.getUuid());String result=s.clock.cancelPhysicalImp(storyteller.getUuid(),key);if(!ClockGame.acceptedResponse("resolve",result))throw new IllegalStateException(result);sync(s,stage.server);return result;}
    public static Map<String,Object> storytellerState(Sessions.Session s){Scene stage=scene(s);if(stage==null||s.clock==null)return Map.of();ClockGame.PhysicalTask task=s.clock.physicalTask();Map<String,Object> state=new LinkedHashMap<>();state.put("physical",true);if(task!=null&&task.role().equals("imp")){state.put("impPending",task.submitted());state.put("impApproved",task.key().equals(stage.approvedKey));state.put("impTaskKey",task.key());if(task.submitted())state.put("impTarget",TabletopPresentation.seat(s,task.targets().getFirst()));state.put("impStatus",!task.submitted()?"等待小恶魔选择本次目标":task.key().equals(stage.approvedKey)?"已允许进入，等待夜刃命中":"等待你确认本次行动");}return state;}
    private static ActionResult attack(ServerPlayerEntity p,Entity target,Hand hand){
        Scene stage=scene(p);if(stage==null){return target instanceof ServerPlayerEntity other&&active(other)?ActionResult.FAIL:ActionResult.PASS;}
        if(target instanceof ServerPlayerEntity player&&hand==Hand.MAIN_HAND&&currentProp(stage,p,p.getMainHandStack(),"knife")){
            ClockGame.PhysicalTask task=stage.session.clock.physicalTask();var targetRoom=room(stage,player.getUuid());if(task!=null&&stage.session.offline.isEmpty()&&stage.session.roster.containsKey(player.getUuid())&&scene(player)==stage&&stage.approvedKey.equals(task.key())&&Objects.equals(stage.approvedTarget,player.getUuid())&&targetRoom!=null&&targetRoom.bounds.contains(p.getX(),p.getY(),p.getZ())&&targetRoom.bounds.contains(player.getX(),player.getY(),player.getZ())&&p.getServerWorld()==stage.world()&&player.getServerWorld()==stage.world()&&p.squaredDistanceTo(player)<=16&&p.canSee(player)){
                boolean effective=stage.session.clock.impEntryPossible(p.getUuid(),player.getUuid());String result=stage.session.clock.resolve(stage.session.host,stage.approvedInfo);if(!ClockGame.acceptedResponse("resolve",result))throw new IllegalStateException(result);if(!effective)deathAnimation(p);Sessions.refresh(stage.session,stage.server);
            }
        }
        return ActionResult.FAIL;
    }
    public static void register(){
        AttackEntityCallback.EVENT.register((p,w,hand,e,hit)->p instanceof ServerPlayerEntity server?attack(server,e,hand):ActionResult.PASS);
        UseItemCallback.EVENT.register((p,w,hand)->{
            if(!(p instanceof ServerPlayerEntity player))return TypedActionResult.pass(p.getStackInHand(hand));Scene stage=scene(player);if(stage==null)return TypedActionResult.pass(p.getStackInHand(hand));ItemStack item=p.getStackInHand(hand);
            if(currentProp(stage,player,item,"self")){try{request(stage,player,player.getUuid());}catch(RuntimeException e){TabletopMessages.warning(player,e.getMessage());}return TypedActionResult.success(item,false);}
            if(managed(item)){use(player,hand);return TypedActionResult.success(item,false);}if(stage.session.clock!=null&&stage.session.clock.isNight()&&!stage.session.host.equals(player.getUuid())&&!item.isOf(TabletopMod.ITEMS.get("phone"))&&!item.isOf(TabletopMod.ITEMS.get("identity_card"))&&!item.isOf(TabletopMod.ITEMS.get("script_card"))&&!item.isOf(TabletopMod.ITEMS.get("rules_book"))&&!item.isOf(TabletopMod.ITEMS.get("faq_book")))return TypedActionResult.fail(item);return TypedActionResult.pass(item);
        });
        UseBlockCallback.EVENT.register((p,w,hand,hit)->{
            if(!(p instanceof ServerPlayerEntity player))return ActionResult.PASS;Scene stage=scene(player);if(stage==null)return ActionResult.PASS;if(ClockRoomDoors.isRoomDoor(w,hit.getBlockPos()))return hand==Hand.MAIN_HAND?doorUse(player,hit.getBlockPos()):ActionResult.FAIL;ItemStack held=player.getStackInHand(hand);if(managed(held)&&(SCENE_PROPS.contains(data(held).getString("kind"))||Set.of("nominate","vote").contains(data(held).getString("kind")))){use(player,hand);return ActionResult.SUCCESS;}
            if(currentProp(stage,player,player.getStackInHand(hand),"self")){try{request(stage,player,player.getUuid());}catch(RuntimeException e){TabletopMessages.warning(player,e.getMessage());}return ActionResult.SUCCESS;}
            if(stage.session.clock!=null&&stage.session.clock.isNight()&&!stage.session.host.equals(player.getUuid()))return doorUse(player,hit.getBlockPos());return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((p,w,hand,e,hit)->{
            if(!(p instanceof ServerPlayerEntity player))return ActionResult.PASS;Scene stage=scene(player);if(stage==null)return ActionResult.PASS;ItemStack held=player.getStackInHand(hand);if(managed(held)&&(SCENE_PROPS.contains(data(held).getString("kind"))||Set.of("nominate","vote","self").contains(data(held).getString("kind")))){use(player,hand);return ActionResult.SUCCESS;}if(e instanceof ServerPlayerEntity target&&scene(target)==stage&&stage.session.clock!=null&&!stage.session.clock.isNight()&&!stage.session.host.equals(player.getUuid())){if(stage.session.clock.publicView().containsKey("vote"))Sessions.openVoting(player);else Sessions.openNomination(player);return ActionResult.SUCCESS;}return stage.session.clock!=null&&stage.session.clock.isNight()?ActionResult.FAIL:ActionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((w,p,pos,state,be)->!ClockRoomDoors.isRoomDoor(w,pos)&&!(p instanceof ServerPlayerEntity player&&active(player)&&!scene(player).session.host.equals(player.getUuid())));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e,source,amount)->!(e instanceof ServerPlayerEntity player)||allowsDamage(player));
        ServerEntityEvents.ENTITY_LOAD.register((e,w)->{if(e instanceof ItemEntity item&&managed(item.getStack()))item.discard();});
    }
    public static void disconnect(ServerPlayerEntity p){Scene stage=scene(p);if(stage!=null){closeDoors(stage);stage.approvedKey="";stage.approvedTarget=null;restoreCostume(stage,p.getUuid());if(stage.session.clock!=null&&stage.session.clock.isNight()&&p.isAlive())home(stage,p);}}
    public static void rejoin(ServerPlayerEntity p){Scene stage=scene(p);if(stage==null){recover(p);return;}if(!p.isAlive())return;p.changeGameMode(stage.session.host.equals(p.getUuid())?GameMode.byId(stage.before.get(p.getUuid()).getInt("mode")):GameMode.ADVENTURE);if(stage.session.clock!=null&&stage.session.clock.isNight()&&!stage.session.host.equals(p.getUuid()))home(stage,p);else seat(stage,p);sync(stage.session,stage.server);}
    private static void recover(ServerPlayerEntity p){Path path=recovery(p);if(!Files.exists(path)||!p.isAlive())return;try{NbtCompound n=NbtIo.readCompressed(path,NbtSizeTracker.ofUnlimitedBytes());restore(p,n,true);var board=p.getScoreboard();var current=board.getScoreHolderTeam(p.getGameProfile().getName());if(current!=null&&current.getName().matches("tt_[0-9a-f]{8}_(st|[1-9]|1[0-2])x*")){board.removeScoreHolderFromTeam(p.getGameProfile().getName(),current);if(current.getPlayerList().isEmpty())board.removeTeam(current);var previous=board.getTeam(n.getString("team"));if(previous!=null)board.addScoreHolderToTeam(p.getGameProfile().getName(),previous);}Files.delete(path);}catch(Exception e){LOGGER.error("Clock scene player recovery failed; snapshot retained",e);throw new IllegalStateException("钟楼场景档案恢复失败，备份已保留。",e);}}
    public static void release(Sessions.Session s,MinecraftServer server){Scene stage=scene(s);if(stage==null)return;closeDoors(stage);SCENES.remove(s.id);for(UUID id:new HashSet<>(stage.costumes.keySet()))restoreCostume(stage,id);for(var entry:stage.before.entrySet()){var p=server.getPlayerManager().getPlayer(entry.getKey());if(p==null)continue;Death death=DEATHS.get(p.getUuid());if(death!=null){if(death.corpse==null&&s.clock!=null&&s.clock.isAlive(death.player))DEATHS.remove(death.player,death);else{if(death.corpse==null&&p.isAlive())executeDeath(death,p);respawn(death);p=server.getPlayerManager().getPlayer(entry.getKey());}}if(p!=null&&p.isAlive()){restore(p,entry.getValue(),true);try{Files.deleteIfExists(recovery(p));}catch(Exception e){LOGGER.error("Clock recovery record retained",e);}}}DEATHS.entrySet().removeIf(e->e.getValue().scene==stage);ClockRoomDoors.restore(server);}
    public static void clear(){for(Scene stage:new ArrayList<>(SCENES.values()))release(stage.session,stage.server);SCENES.clear();DEATHS.clear();AUTHORIZED_DEATH.clear();RESTORING.clear();ClockArena.clear();}
}
