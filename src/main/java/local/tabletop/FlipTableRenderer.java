package local.tabletop;

import local.tabletop.game.FlipGame;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Function;

/** Public table props. The scene animates confirmed reveals and never reads or changes the deck. */
public final class FlipTableRenderer {
    private FlipTableRenderer() {}

    /** Owned by one session; reusable displays prevent every draw from respawning the whole table. */
    public static final class Scene {
        private static final int TABLE_COLUMNS=5;
        private static final double TABLE_ROW_GAP=.56;
        private static final int TURNOVER_TICKS=12;
        // _card.json ground translation is 1.5 model pixels above the item origin.
        private static final double CARD_GROUND_LIFT=1.5/16.0;
        private final Map<String,Node> nodes=new LinkedHashMap<>();
        private final List<Flight> flights=new ArrayList<>();
        private final List<Turnover> turnovers=new ArrayList<>();
        private final List<Entity> transientEntities=new ArrayList<>();
        private final Map<UUID,DisplayEntity.TextDisplayEntity> headScores=new LinkedHashMap<>();
        private ServerWorld sceneWorld;
        private int round=-1;
        private double originX,originY,originZ;

        public List<Entity> entities(){var all=new ArrayList<Entity>();for(Node n:nodes.values())all.add(n.entity);all.addAll(transientEntities);return all;}
        public void clear(){for(Entity e:entities())e.discard();nodes.clear();flights.clear();turnovers.clear();transientEntities.clear();headScores.clear();sceneWorld=null;round=-1;}

        public void update(ServerWorld world,BlockPos anchor,Map<String,Object> state,Function<UUID,String> names,List<FlipGame.Reveal> reveals,long now){
            update(world,anchor,state,names,reveals,now,Map.of());
        }
        public void update(ServerWorld world,BlockPos anchor,Map<String,Object> state,Function<UUID,String> names,List<FlipGame.Reveal> reveals,long now,Map<Integer,FlipTableLayout.Point> tableSeats){
            if(!(state.get("players") instanceof Map<?,?> players))return;
            int nextRound=((Number)state.getOrDefault("round",0)).intValue();if(round!=nextRound){clear();round=nextRound;}
            sceneWorld=world;
            originX=anchor.getX()+.5;originY=anchor.getY()+.025;originZ=anchor.getZ()+.5;
            double radius=players.size()<=9?2.25:2.25+(players.size()-9)*.15;
            Set<String> used=new HashSet<>();Set<FlipGame.Reveal> settled=new HashSet<>();
            int index=0;
            for(var entry:players.entrySet()){
                if(!(entry.getValue() instanceof Map<?,?> seat))continue;
                UUID id;try{id=UUID.fromString(String.valueOf(entry.getKey()));}catch(IllegalArgumentException e){continue;}
                int number=seat.get("seat") instanceof Number fixed&&fixed.intValue()>=1&&fixed.intValue()<=8?fixed.intValue():index+1;
                boolean fixedSeat=seat.get("seat") instanceof Number;
                double angle=Math.PI/2+2*Math.PI*(fixedSeat?number-1:index)/(fixedSeat?8:Math.max(1,players.size()));
                FlipTableLayout.Point tableSeat=tableSeats.get(number);
                if(tableSeat!=null){double yaw=Math.toRadians(tableSeat.yaw());angle=Math.atan2(-Math.cos(yaw),Math.sin(yaw));}
                double rx=Math.cos(angle),rz=Math.sin(angle),tx=Math.sin(angle),tz=-Math.cos(angle);
                double cx=tableSeat==null?rx*radius:tableSeat.x()-originX,cy=tableSeat==null?0:tableSeat.y()-originY,cz=tableSeat==null?rz*radius:tableSeat.z()-originZ;
                float rotation=(float)(Math.PI/2-angle);
                var heldNumbers=new ArrayList<String>();var heldModifiers=new ArrayList<String>();
                if(seat.get("cards") instanceof Collection<?> cards)for(Object card:cards){String key=String.valueOf(card);if(key.startsWith("number_"))heldNumbers.add(key);else heldModifiers.add(key);}
                var numbers=new ArrayList<>(heldNumbers);var modifiers=new ArrayList<>(heldModifiers);
                // Spent Freeze / busting duplicate cards remain physically on the table this round.
                if(seat.get("tableCards") instanceof Collection<?> tableCards){numbers.clear();modifiers.clear();for(Object card:tableCards){String key=String.valueOf(card);if(key.startsWith("number_"))numbers.add(key);else modifiers.add(key);}}
                String status=String.valueOf(seat.get("status"));
                // Bust is public evidence: both matching cards stay face-up for everyone.
                boolean turnOver=status.equals("stayed")||status.equals("frozen");
                int numberRows=Math.max(1,(numbers.size()+TABLE_COLUMNS-1)/TABLE_COLUMNS),effectRows=(modifiers.size()+TABLE_COLUMNS-1)/TABLE_COLUMNS;
                // Keep every fixed place inside its own tabletop strip, even after many action cards.
                double rowGap=tableSeat==null?TABLE_ROW_GAP:Math.min(TABLE_ROW_GAP,1.4/Math.max(1,numberRows+effectRows-1));
                float cardSize=(float)Math.min(1,rowGap/.50),numberScale=.65f*cardSize,effectScale=.60f*cardSize;
                double effectDepth=numberRows*rowGap+.06;
                placeCards(world,used,settled,reveals,id,numbers,"number",cx,cy,cz,tx,tz,rx,rz,rotation,numberScale,.40,rowGap,turnOver,now);
                placeCards(world,used,settled,reveals,id,modifiers,"effect",cx-rx*effectDepth,cy,cz-rz*effectDepth,tx,tz,rx,rz,rotation,effectScale,.38,rowGap,turnOver,now);
                String badge=switch(status){case "busted"->"已死 · 爆牌，本轮 0";case "stayed"->"已收手";case "frozen"->"已冻结";default->Objects.equals(String.valueOf(entry.getKey()),String.valueOf(state.get("turn")))?"正在翻牌":"等待";};
                label(world,used,"seat:"+id,number+"号 · "+names.apply(id)+"\n累计 "+seat.get("total")+" · 本轮 "+seat.get("roundScore")+" · "+badge,cx+rx*.35,cy+.25,cz+rz*.35,.23f);
                label(world,used,"head:"+id,"累计 "+seat.get("total")+" 分  |  本轮 "+seat.get("roundScore")+" 分\n"+badge,cx,cy+2.3,cz,.40f);
                headScores.put(id,(DisplayEntity.TextDisplayEntity)nodes.get("head:"+id).entity);
                // Revealed action/discard cards remain visible briefly even if not retained in the hand.
                for(FlipGame.Reveal reveal:reveals)if(reveal.player().equals(id)&&!settled.contains(reveal)){
                    var e=item(world,reveal.cardId());transientEntities.add(e);
                    flights.add(new Flight(e,reveal.cardId(),cx-rx*.16,cy+.10,cz-rz*.16,rotation,.82f,now+Math.min(20,reveals.indexOf(reveal)*4),true));
                    settled.add(reveal);
                }
                index++;
            }
            stableItem(world,used,"deck","flip_deck",-.25,.07,0,.86f,0);
            stableItem(world,used,"book","flip_rules_book",.38,.05,0,.60f,0);
            String instruction=String.valueOf(state.getOrDefault("flowTitle","七连翻"));
            if(state.get("turn")!=null)try{instruction+=" · "+names.apply(UUID.fromString(state.get("turn").toString()));}catch(IllegalArgumentException ignored){}
            label(world,used,"center",instruction,0,.46,0,.29f);
            Iterator<Map.Entry<String,Node>> it=nodes.entrySet().iterator();while(it.hasNext()){Node n=it.next().getValue();if(!used.contains(n.id)){n.entity.discard();flights.removeIf(f->f.entity==n.entity);turnovers.removeIf(t->t.node==n);it.remove();}}
            tick(now);
        }

        private void placeCards(ServerWorld world,Set<String> used,Set<FlipGame.Reveal> settled,List<FlipGame.Reveal> reveals,UUID owner,List<String> cards,String group,double cx,double y,double cz,double tx,double tz,double rx,double rz,float rotation,float scale,double gap,double rowGap,boolean inactive,long now){
            for(int i=0;i<cards.size();i++){
                int row=i/TABLE_COLUMNS,count=Math.min(TABLE_COLUMNS,cards.size()-row*TABLE_COLUMNS);double offset=(i%TABLE_COLUMNS-(count-1)/2.0)*gap;
                double x=cx+tx*offset-rx*row*rowGap,z=cz+tz*offset-rz*row*rowGap;
                float angle=rotation;
                String key=cards.get(i),nodeId=owner+":"+group+":"+i;Node previous=nodes.get(nodeId);
                boolean fresh=previous==null||previous.entity.isRemoved()||!previous.key.equals(key);
                Node node=stableItem(world,used,nodeId,key,x,y,z,scale,angle);
                FlipGame.Reveal reveal=reveals.stream().filter(r->r.player().equals(owner)&&r.cardId().equals(key)&&!settled.contains(r)).findFirst().orElse(null);
                if(fresh&&reveal!=null){
                    settled.add(reveal);flights.removeIf(f->f.entity==node.entity);
                    flights.add(new Flight((DisplayEntity.ItemDisplayEntity)node.entity,key,x,y,z,angle,scale,now+Math.min(20,reveals.indexOf(reveal)*4),false));
                }else for(Flight flight:flights)if(flight.entity==node.entity){flight.x=x;flight.y=y;flight.z=z;flight.rotation=angle;}
                if(inactive&&!node.faceDown){
                    node.faceDown=true;long start=now;
                    // Finish a newly dealt card's flight before changing the same transform again.
                    for(Flight flight:flights)if(flight.entity==node.entity)start=Math.max(start,flight.start+18);
                    turnovers.add(new Turnover(node,start));
                }
            }
        }
        private Node stableItem(ServerWorld world,Set<String> used,String id,String key,double x,double y,double z,float scale,float rotation){
            used.add(id);Node n=nodes.get(id);
            if(n==null||n.entity.isRemoved()||!n.key.equals(key)){
                if(n!=null){n.entity.discard();Node old=n;flights.removeIf(f->f.entity==old.entity);turnovers.removeIf(t->t.node==old);}
                n=new Node(id,key,item(world,key));nodes.put(id,n);
            }
            n.tablePose=new TablePose(x,y,z,scale,rotation);
            Node current=n;if(flights.stream().noneMatch(f->f.entity==current.entity)&&turnovers.stream().noneMatch(t->t.node==current))
                pose((DisplayEntity.ItemDisplayEntity)n.entity,x,y+(n.faceDown?2*CARD_GROUND_LIFT*scale:0),z,scale,rotation,n.faceDown?(float)Math.PI:0);
            return n;
        }
        private DisplayEntity.ItemDisplayEntity item(ServerWorld world,String key){
            var e=new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY,world);e.setItemStack(stack(key));e.setTransformationMode(ModelTransformationMode.GROUND);
            e.setPosition(originX,originY,originZ);e.setViewRange(.8f);e.addCommandTag("tabletop_flip_display");world.spawnEntity(e);return e;
        }
        private void label(ServerWorld world,Set<String> used,String id,String text,double x,double y,double z,float scale){
            used.add(id);Node n=nodes.get(id);if(n==null||n.entity.isRemoved()){
                var e=new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY,world);e.setBillboardMode(DisplayEntity.BillboardMode.CENTER);world.spawnEntity(e);n=new Node(id,"text",e);nodes.put(id,n);
            }
            var e=(DisplayEntity.TextDisplayEntity)n.entity;e.setText(Text.literal(text));e.setPosition(originX+x,originY+y,originZ+z);
            if(id.startsWith("head:")){e.setBackground(0xb0141d26);e.setDisplayFlags(DisplayEntity.TextDisplayEntity.SHADOW_FLAG);e.setViewRange(1.5f);}
            e.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f(scale),new Quaternionf()));
        }
        /** A bounded list of display transforms; no scans of players, chunks or unrelated entities. */
        public void tick(long now){
            if(sceneWorld!=null&&now%5==0)for(var entry:headScores.entrySet()){
                var player=sceneWorld.getServer().getPlayerManager().getPlayer(entry.getKey());var display=entry.getValue();
                if(display.isRemoved())continue;
                boolean visible=player!=null&&player.getWorld()==sceneWorld;
                if(visible)display.setPosition(player.getX(),player.getEyeY()+1.05,player.getZ());
                display.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f(visible?.40f:0),new Quaternionf()));
            }
            Iterator<Flight> it=flights.iterator();while(it.hasNext()){
                Flight f=it.next();if(f.entity.isRemoved()){transientEntities.remove(f.entity);it.remove();continue;}
                double t=Math.clamp((now-f.start)/18.0,0,1),ease=t*t*(3-2*t);
                boolean face=t>=.5;if(face!=f.face){f.entity.setItemStack(stack(face?f.key:"card_back"));f.face=face;}
                double lift=.45*Math.sin(Math.PI*t);
                pose(f.entity,-.25+(f.x+.25)*ease,.09+(f.y-.09)*ease+lift,f.z*ease,f.scale,f.rotation,(float)(Math.PI*(1-t)));
                if(t>=1&&(!f.temporary||now-f.start>=34)){
                    if(f.temporary){f.entity.discard();transientEntities.remove(f.entity);}it.remove();
                }
            }
            Iterator<Turnover> turning=turnovers.iterator();while(turning.hasNext()){
                Turnover t=turning.next();if(t.node.entity.isRemoved()){turning.remove();continue;}
                if(now<t.start)continue;
                double progress=Math.clamp((now-t.start)/(double)TURNOVER_TICKS,0,1),ease=progress*progress*(3-2*progress);
                TablePose p=t.node.tablePose;
                double lift=.27*Math.sin(Math.PI*progress);
                pose((DisplayEntity.ItemDisplayEntity)t.node.entity,p.x,p.y+2*CARD_GROUND_LIFT*p.scale*ease+lift,p.z,p.scale,p.rotation,(float)(Math.PI*ease));
                if(progress>=1)turning.remove();
            }
        }
        private static ItemStack stack(String key){return new ItemStack(TabletopMod.ITEMS.getOrDefault(key,TabletopMod.ITEMS.get("card_back")));}
        private static void pose(DisplayEntity.ItemDisplayEntity e,double x,double y,double z,float scale,float rotation,float flip){
            e.setTransformation(new AffineTransformation(new Vector3f((float)x,(float)y,(float)z),new Quaternionf().rotateY(rotation).rotateX(flip),new Vector3f(scale),new Quaternionf()));
        }
        private static final class Node {
            final String id,key;final Entity entity;boolean faceDown;TablePose tablePose;
            Node(String id,String key,Entity entity){this.id=id;this.key=key;this.entity=entity;}
        }
        private record TablePose(double x,double y,double z,float scale,float rotation){}
        private record Turnover(Node node,long start){}
        private static final class Flight {
            final DisplayEntity.ItemDisplayEntity entity;final String key;final float scale;final long start;final boolean temporary;
            double x,y,z;float rotation;boolean face=true;
            Flight(DisplayEntity.ItemDisplayEntity entity,String key,double x,double y,double z,float rotation,float scale,long start,boolean temporary){this.entity=entity;this.key=key;this.x=x;this.y=y;this.z=z;this.rotation=rotation;this.scale=scale;this.start=start;this.temporary=temporary;}
        }
    }
}
