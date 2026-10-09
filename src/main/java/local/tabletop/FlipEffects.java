package local.tabletop;

import local.tabletop.game.FlipGame;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import java.util.List;

/** Cosmetic particles and sound only: no explosion, lightning entity, fire or damage. */
final class FlipEffects {
    static void play(ServerWorld world,List<FlipGame.Effect> events){
        for(var event:events){
            var p=world.getServer().getPlayerManager().getPlayer(event.player());
            if(p==null||p.getServerWorld()!=world)continue;
            double x=p.getX(),y=p.getY()+1.1,z=p.getZ();
            switch(event.kind()){
                case "bust"->{
                    world.spawnParticles(ParticleTypes.EXPLOSION,x,y,z,2,.25,.25,.25,0);
                    world.spawnParticles(ParticleTypes.POOF,x,y,z,18,.4,.35,.4,.025);
                    world.playSound(null,x,y,z,SoundEvents.ENTITY_GENERIC_EXPLODE,SoundCategory.PLAYERS,.35f,1.4f);
                }
                case "lightning"->{
                    for(int i=0;i<16;i++){double a=i*Math.PI/4;world.spawnParticles(ParticleTypes.ELECTRIC_SPARK,x+Math.cos(a)*.42,p.getY()+.15+i*.12,z+Math.sin(a)*.42,3,.04,.06,.04,.03);}
                    world.playSound(null,x,y,z,SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT,SoundCategory.PLAYERS,.28f,1.6f);
                }
                case "freeze"->{world.spawnParticles(ParticleTypes.SNOWFLAKE,x,y,z,24,.45,.65,.45,.025);world.playSound(null,x,y,z,SoundEvents.BLOCK_GLASS_PLACE,SoundCategory.PLAYERS,.4f,.8f);}
                case "shield_break"->{world.spawnParticles(ParticleTypes.WAX_OFF,x,y,z,24,.45,.55,.45,.05);world.playSound(null,x,y,z,SoundEvents.BLOCK_GLASS_BREAK,SoundCategory.PLAYERS,.35f,1.3f);}
            }
        }
    }
}
