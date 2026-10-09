package local.tabletop;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Color adds emphasis without adding another notification or exposing hidden state. */
public final class TabletopMessages {
    private TabletopMessages(){}
    static Text line(String label,Formatting color,String message){
        return Text.literal("["+label+"] ").formatted(color,Formatting.BOLD)
            .append(Text.literal(message).formatted(Formatting.GRAY).styled(style->style.withBold(false)));
    }
    static Text publicLine(String type,String message){
        if(message.contains("获胜")||message.contains("结算"))return line("结算",Formatting.GOLD,message);
        if(message.contains("提名")||message.contains("投票")||message.contains("处决"))return line("投票",Formatting.AQUA,message);
        if(message.contains("天亮")||message.contains("白天"))return line("白天",Formatting.BLUE,message);
        if(message.contains("夜")||message.contains("回房"))return line("夜晚",Formatting.RED,message);
        return line(type.equals("clock")?"钟楼":"七连翻",Formatting.GREEN,message);
    }
    public static void info(ServerPlayerEntity p,String message){p.sendMessage(line("提示",Formatting.AQUA,message),true);}
    public static void warning(ServerPlayerEntity p,String message){p.sendMessage(line("提示",Formatting.RED,message),true);}
    public static void night(ServerPlayerEntity p,String message){p.sendMessage(line("夜间",Formatting.RED,message),false);}
    static Text privateLine(String message){return line("说书人",Formatting.LIGHT_PURPLE,message);}
}
