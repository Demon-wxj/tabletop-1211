package local.tabletop.client;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/** A table's personal sky clock. The room and phone remain visible throughout the night. */
public final class NightCurtain {
    private static boolean active,night;
    private static String dimension="";
    static boolean sleeping(){return false;}
    static void accept(JsonObject data){
        active=data.has("active")&&data.get("active").getAsBoolean();night=data.has("night")&&data.get("night").getAsBoolean();dimension=TabletopClient.str(data,"dimension");enforceTime();
    }
    static void reset(){active=false;night=false;dimension="";}
    static void initialize(){ClientTickEvents.END_CLIENT_TICK.register(client->enforceTime());}
    public static void enforceTime(){
        var world=MinecraftClient.getInstance().world;
        if(active&&world!=null&&world.getRegistryKey().getValue().toString().equals(dimension))world.setTimeOfDay(night?-18000:-1000);
    }
}
