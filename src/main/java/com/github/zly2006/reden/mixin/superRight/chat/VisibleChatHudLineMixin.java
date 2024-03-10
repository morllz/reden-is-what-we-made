package com.github.zly2006.reden.mixin.superRight.chat;

import com.github.zly2006.reden.access.VisibleChatHudLineAccess;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChatHudLine.Visible.class)
public class VisibleChatHudLineMixin implements VisibleChatHudLineAccess {
    @Unique private Text text;
    @Override
    public Text getText$reden() {
        return this.text;
    }

    @Override
    public void setText$reden(Text text) {
        this.text = text;
    }
}
