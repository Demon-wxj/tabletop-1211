package local.tabletop;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.*;
import local.tabletop.game.ClockSeats;

/** Public seating and phase presentation, scoped to this table's audience. */
final class TabletopPresentation {
    private record Label(MinecraftServer server,String name,String previous,String owned) {}
    private record Sleep(ServerPlayerEntity player,StatusEffectInstance previous,int tick) {}
    private static final Map<UUID,Label> LABELS=new HashMap<>();
    private static final Map<UUID,Sleep> SLEEPERS=new HashMap<>();
    private static final Map<UUID,String> VISION=new HashMap<>();
    private static final Map<String,ServerBossBar> PHASE_BARS=new HashMap<>();
    private static final Map<UUID,net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity> SEAT_LABELS=new HashMap<>();
    static int seat(Sessions.Session s,UUID id){if(s.type.equals("clock")){Sessions.clockPlayers(s);return s.clockSeats.number(id);}return Sessions.flipSeat(s,id);}
    static boolean storyteller(Sessions.Session s,UUID id){return s.type.equals("clock")&&s.host.equals(id);}
    static String originalTeam(ServerPlayerEntity player){Label known=LABELS.get(player.getUuid());if(known!=null)return known.previous;Team team=player.getServer().getScoreboard().getScoreHolderTeam(player.getGameProfile().getName());return team==null?"":team.getName();}
    static int color(Sessions.Session s,UUID id){return storyteller(s,id)?0xffd16b:s.type.equals("clock")?ClockSeats.color(seat(s,id)):FlipSeats.color(seat(s,id));}
    static void labels(Sessions.Session s,MinecraftServer server){
        var board=server.getScoreboard();
        for(UUID id:s.audience()){
            var player=server.getPlayerManager().getPlayer(id);if(player==null)continue;
            String name=player.getGameProfile().getName();Label known=LABELS.get(id);
            if(known==null){Team previous=board.getScoreHolderTeam(name);String owned="tt_"+s.id+"_"+(storyteller(s,id)?"st":seat(s,id));
                // A namespaced team is never adopted from another owner.
                while(board.getTeam(owned)!=null)owned+="x";
                known=new Label(server,name,previous==null?"":previous.getName(),owned);LABELS.put(id,known);board.addTeam(owned);
            }
            Team team=board.getTeam(known.owned);if(team==null)continue;
            boolean disguised=ClockEmbodiment.disguised(id);
            Text prefix=disguised?Text.empty():Text.literal(storyteller(s,id)?"说书人 ":seat(s,id)+"号 ").styled(style->style.withColor(color(s,id)));
            if(!team.getPrefix().equals(prefix))team.setPrefix(prefix);
            var teamColor=storyteller(s,id)?Formatting.GOLD:Formatting.WHITE;if(team.getColor()!=teamColor)team.setColor(teamColor);
            var visibility=s.type.equals("clock")||disguised?AbstractTeam.VisibilityRule.NEVER:AbstractTeam.VisibilityRule.ALWAYS;
            if(team.getNameTagVisibilityRule()!=visibility)team.setNameTagVisibilityRule(visibility);
            if(team.shouldShowFriendlyInvisibles())team.setShowFriendlyInvisibles(false);
            if(board.getScoreHolderTeam(name)!=team)board.addScoreHolderToTeam(name,team);
        }
        tickLabels(s,server);
    }
    /** Clock exposes only seat numbers in the world, rather than profile IDs or team titles. */
    static void tickLabels(Sessions.Session s,MinecraftServer server){
        if(!s.type.equals("clock"))return;
        for(UUID id:s.audience()){
            var player=server.getPlayerManager().getPlayer(id);var label=SEAT_LABELS.get(id);
            boolean visible=player!=null&&player.isAlive()&&player.getWorld().getRegistryKey().equals(s.dimension)&&!ClockEmbodiment.disguised(id)&&(storyteller(s,id)||s.clock==null||!s.clock.isNight());
            if(label==null&&player!=null){label=new net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity(net.minecraft.entity.EntityType.TEXT_DISPLAY,player.getServerWorld());label.setBillboardMode(net.minecraft.entity.decoration.DisplayEntity.BillboardMode.CENTER);label.setViewRange(1);label.addCommandTag("tabletop_clock_seat_label");player.getServerWorld().spawnEntity(label);SEAT_LABELS.put(id,label);}
            if(label==null)continue;
            if(player!=null&&label.getWorld()!=player.getWorld()){label.discard();SEAT_LABELS.remove(id);continue;}
            label.setText(Text.literal(storyteller(s,id)?"说书人":seat(s,id)+"号").styled(style->style.withColor(color(s,id))));
            if(visible)label.setPosition(player.getX(),player.getEyeY()+.38,player.getZ());
            label.setTransformation(new net.minecraft.util.math.AffineTransformation(new org.joml.Vector3f(),new org.joml.Quaternionf(),new org.joml.Vector3f(visible?.35f:0),new org.joml.Quaternionf()));
        }
    }
    static void night(Sessions.Session s,MinecraftServer server){
        boolean active=s.type.equals("clock")&&s.running&&s.clock!=null&&!s.ended();boolean night=active&&s.clock.isNight();
        phaseBar(s,server,active,night);
        for(UUID id:s.audience()){
            var player=server.getPlayerManager().getPlayer(id);if(player==null)continue;
            restoreVision(id);
            boolean personal=active&&player.getWorld().getRegistryKey().equals(s.dimension);
            String stamp=s.id+":"+personal+":"+night;
            if(!stamp.equals(VISION.put(id,stamp))){Sessions.sendPresentation(player,Map.of("kind","night_vision","session",s.id,"active",personal,"night",night,"dimension",s.dimension.getValue().toString()));if(!personal)resetTime(player);}
            if(personal)player.networkHandler.sendPacket(new WorldTimeUpdateS2CPacket(player.getServerWorld().getTime(),night?18000:1000,false));
        }
    }
    private static void phaseBar(Sessions.Session s,MinecraftServer server,boolean active,boolean night){
        if(!active){ServerBossBar old=PHASE_BARS.remove(s.id);if(old!=null)old.clearPlayers();return;}
        ServerBossBar bar=PHASE_BARS.computeIfAbsent(s.id,key->{var created=new ServerBossBar(Text.literal("白天"),BossBar.Color.BLUE,BossBar.Style.PROGRESS);created.setPercent(1);created.setDarkenSky(false);created.setDragonMusic(false);created.setThickenFog(false);return created;});
        bar.setName(Text.literal(night?"夜晚":"白天"));bar.setColor(night?BossBar.Color.RED:BossBar.Color.BLUE);bar.setPercent(1);bar.setVisible(true);
        var audience=s.audience();for(var player:new ArrayList<>(bar.getPlayers()))if(!audience.contains(player.getUuid())||server.getPlayerManager().getPlayer(player.getUuid())!=player)bar.removePlayer(player);
        for(UUID id:audience){var player=server.getPlayerManager().getPlayer(id);if(player!=null)bar.addPlayer(player);}
    }
    private static void resetTime(ServerPlayerEntity player){var world=player.getServerWorld();player.networkHandler.sendPacket(new WorldTimeUpdateS2CPacket(world.getTime(),world.getTimeOfDay(),world.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE)));}
    private static void restoreVision(UUID id){
        Sleep sleep=SLEEPERS.remove(id);if(sleep==null)return;
        var player=sleep.player;var current=player.getStatusEffect(StatusEffects.BLINDNESS);
        if(current!=null&&current.isAmbient()&&!current.shouldShowParticles()&&!current.shouldShowIcon()&&current.getAmplifier()==0)player.removeStatusEffect(StatusEffects.BLINDNESS);
        if(sleep.previous!=null){var old=sleep.previous;int remaining=old.getDuration()<0?-1:old.getDuration()-(player.getServer().getTicks()-sleep.tick);
            if(remaining!=0&&(remaining>0||old.getDuration()<0))player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS,remaining,old.getAmplifier(),old.isAmbient(),old.shouldShowParticles(),old.shouldShowIcon()));}
    }
    static void disconnect(ServerPlayerEntity player){restoreVision(player.getUuid());VISION.remove(player.getUuid());for(var bar:PHASE_BARS.values())bar.removePlayer(player);}
    static void release(UUID id){
        var seatLabel=SEAT_LABELS.remove(id);if(seatLabel!=null)seatLabel.discard();
        restoreVision(id);VISION.remove(id);Label label=LABELS.remove(id);if(label==null)return;
        var board=label.server.getScoreboard();Team owned=board.getTeam(label.owned);
        boolean ours=owned!=null&&board.getScoreHolderTeam(label.name)==owned;
        if(ours)board.removeScoreHolderFromTeam(label.name,owned);
        if(owned!=null)board.removeTeam(owned);
        if(ours&&!label.previous.isBlank()){Team old=board.getTeam(label.previous);if(old!=null)board.addScoreHolderToTeam(label.name,old);}
        var player=label.server.getPlayerManager().getPlayer(id);if(player!=null){for(var bar:PHASE_BARS.values())bar.removePlayer(player);Sessions.sendPresentation(player,Map.of("kind","night_vision","active",false,"night",false));resetTime(player);}
        PHASE_BARS.entrySet().removeIf(entry->{if(!entry.getValue().getPlayers().isEmpty())return false;entry.getValue().clearPlayers();return true;});
    }
    static void clear(){for(UUID id:new HashSet<>(LABELS.keySet()))release(id);for(var label:SEAT_LABELS.values())label.discard();SEAT_LABELS.clear();for(UUID id:new HashSet<>(SLEEPERS.keySet()))restoreVision(id);for(var bar:PHASE_BARS.values())bar.clearPlayers();PHASE_BARS.clear();VISION.clear();}
}
