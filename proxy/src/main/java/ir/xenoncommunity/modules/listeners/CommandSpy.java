package ir.xenoncommunity.modules.listeners;

import ir.xenoncommunity.XenonCore;
import ir.xenoncommunity.abstracts.ModuleListener;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Arrays;

public class CommandSpy extends ModuleListener implements Listener {
    @EventHandler
    public void onCommand(final ChatEvent e) {
        if(!e.getMessage().startsWith("/")
            || !(e.getSender() instanceof ProxiedPlayer)) return;

        final ProxiedPlayer player = (ProxiedPlayer) e.getSender();
        if(player.hasPermission(XenonCore.instance.getConfigData().getModules().getSpybypass())) return;

        final String rawCommand = e.getMessage();
        final String command = rawCommand.substring(1).toLowerCase();

        XenonCore.instance.getTaskManager().add(() -> {
            if (Arrays.stream(XenonCore.instance.getConfigData().getModules().getSpyexceptions())
                    .map(String::toLowerCase)
                    .anyMatch(command::contains)) return;


            XenonCore.instance.getBungeeInstance().getPlayers().stream().filter(
                    proxiedPlayer -> proxiedPlayer.hasPermission(XenonCore.instance.getConfigData().getModules().getSpyperm())).forEach(
                    proxiedPlayer -> {
                        proxiedPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                XenonCore.instance.getConfigData().getModules().getSpymessage()
                                        .replace("PLAYER", player.getDisplayName())
                                        .replace("COMMAND", rawCommand)));
                    });
        });
    }
}
