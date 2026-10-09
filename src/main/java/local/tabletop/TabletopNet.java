package local.tabletop;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class TabletopNet {
    private TabletopNet() {}
    public record Action(String action, String target, String text, String context) implements CustomPayload {
        public Action(String action,String target,String text){this(action,target,text,"");}
        public static final Id<Action> ID = new Id<>(Identifier.of("tabletop", "action_v1"));
        public static final PacketCodec<RegistryByteBuf, Action> CODEC = PacketCodec.of(
            (p,b) -> { b.writeString(p.action,32); b.writeString(p.target,640); b.writeString(p.text,600); b.writeString(p.context,96); },
            b -> new Action(b.readString(32),b.readString(640),b.readString(600),b.isReadable()?b.readString(96):""));
        public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record View(String json, boolean open) implements CustomPayload {
        public static final Id<View> ID = new Id<>(Identifier.of("tabletop", "view_v1"));
        public static final PacketCodec<RegistryByteBuf, View> CODEC = PacketCodec.of(
            (p,b) -> { b.writeString(p.json,32767); b.writeBoolean(p.open); },
            b -> new View(b.readString(32767),b.readBoolean()));
        public Id<? extends CustomPayload> getId() { return ID; }
    }
}
