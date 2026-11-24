package local.tabletop.check;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.nio.file.*;
import java.util.*;
public final class ProfileResolution implements PreLaunchEntrypoint {
    public void onPreLaunch(){if(!Boolean.getBoolean("tabletop.check.resolution"))return;try{var mods=new TreeMap<String,String>();for(var m:FabricLoader.getInstance().getAllMods())mods.put(m.getMetadata().getId(),m.getMetadata().getVersion().getFriendlyString());Files.writeString(Path.of("resolved.json"),new Gson().toJson(mods));System.exit(0);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
