package net.md_5.bungee.connection;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import io.netty.channel.Channel;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.ServerConnection.KeepAliveData;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.entitymap.EntityMap;
import net.md_5.bungee.forge.ForgeConstants;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.*;
import net.md_5.bungee.util.AllowedCharacters;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class UpstreamBridge extends PacketHandler
{

    private final ProxyServer bungee;
    private final UserConnection con;

    private long lastTabCompletion = -1;

    public UpstreamBridge(ProxyServer bungee, UserConnection con)
    {
        this.bungee = bungee;
        this.con = con;

        con.getTabListHandler().onConnect();
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        con.disconnect( Util.exception( t ) );
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        // We lost connection to the client
        PlayerDisconnectEvent event = new PlayerDisconnectEvent( con );
        bungee.getPluginManager().callEvent( event );
        con.getTabListHandler().onDisconnect();
        BungeeCord.getInstance().removeConnection( con );

        if ( con.getServer() == null) return;

        final PlayerListItem oldPacket = new PlayerListItem();
        final PlayerListItem.Item item = new PlayerListItem.Item();
        oldPacket.setAction( PlayerListItem.Action.REMOVE_PLAYER );
        item.setUuid( con.getRewriteId() );
        oldPacket.setItems( new PlayerListItem.Item[]
                {
                        item
                } );

        final PlayerListItemRemove newPacket = new PlayerListItemRemove();
        newPacket.setUuids( new UUID[]
                {
                        con.getRewriteId()
                } );

        con.getServer().getInfo().getPlayers().forEach(player -> {
            if ( player.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_19_3 )
            {
                player.unsafe().sendPacket( newPacket );
            } else
            {
                player.unsafe().sendPacket( oldPacket );
            }
        });
        con.getServer().disconnect( "Quitting" );
    }

    @Override
    public void writabilityChanged(ChannelWrapper channel) {
        if ( con.getServer() == null )return;

        Channel server = con.getServer().getCh().getHandle();
        server.config().setAutoRead(channel.getHandle().isWritable());
    }

    @Override
    public boolean shouldHandle(PacketWrapper packet) {
        return con.getServer() != null || packet.packet instanceof PluginMessage || packet.packet instanceof CookieResponse;
    }

    @Override
    public void handle(PacketWrapper packet) throws Exception
    {
        ServerConnection server = con.getServer();
        if ( server != null && server.isConnected() )
        {
            Protocol serverEncode = server.getCh().getEncodeProtocol();
            // #3527: May still have old packets from client in game state when switching server to configuration state - discard those
            if ( packet.protocol != serverEncode )
            {
                return;
            }

            EntityMap rewrite = con.getEntityRewrite();
            if ( rewrite != null && serverEncode == Protocol.GAME )
            {
                rewrite.rewriteServerbound( packet.buf, con.getClientEntityId(), con.getServerEntityId(), con.getPendingConnection().getVersion() );
            }
            server.getCh().write( packet );
        }
    }

    @Override
    public void handle(KeepAlive alive) throws Exception
    {
        KeepAliveData keepAliveData = con.getServer().getKeepAlives().peek();

        if ( keepAliveData != null && alive.getRandomId() == keepAliveData.getId() )
        {
            Preconditions.checkState( keepAliveData == con.getServer().getKeepAlives().poll(), "keepalive queue mismatch" );
            int newPing = (int) ( System.currentTimeMillis() - keepAliveData.getTime() );
            con.getTabListHandler().onPingChange( newPing );
            con.setPing( newPing );
        } else
        {
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public void handle(Chat chat) throws Exception
    {
        String message = handleChat( chat.getMessage() );
        if ( message != null )
        {
            chat.setMessage( message );
            con.getServer().unsafe().sendPacket( chat );
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(ClientChat chat) throws Exception
    {
        handleChat( chat.getMessage() );
    }

    @Override
    public void handle(ClientCommand command) throws Exception
    {
        handleChat( "/" + command.getCommand(), command ); // Waterfall
    }

    @Override
    public void handle(UnsignedClientCommand command) throws Exception
    {
        handleChat( "/" + command.getCommand() );
    }

    // Waterfall start
    private String handleChat(String message) {
        return handleChat(message, null);
    }
    private String handleChat(String message, @javax.annotation.Nullable ClientCommand clientCommand)
    // Waterfall end
    {
        boolean empty = true;
        for ( int index = 0, length = message.length(); index < length; index++ )
        {
            char c = message.charAt( index );
            if ( !AllowedCharacters.isChatAllowedCharacter( c ) )
            {
                con.disconnect( bungee.getTranslation( "illegal_chat_characters", Util.unicode( c ) ) );
                throw CancelSendSignal.INSTANCE;
            } else if (empty && !Character.isWhitespace(c)) {
                empty = false;
            }
        }
        if (empty) {
            con.disconnect("Chat message is empty");
            throw CancelSendSignal.INSTANCE;
        }

        ChatEvent chatEvent = new ChatEvent( con, con.getServer(), message );
        if ( !bungee.getPluginManager().callEvent( chatEvent ).isCancelled() )
        {
            message = chatEvent.getMessage();
            if ( !chatEvent.isCommand() || !bungee.getPluginManager().dispatchCommand( con, message.substring( 1 ) ) )
            {
                return message;
                // Waterfall start - We're going to cancel this packet, so, no matter what, we might as well try to send this
            } else if(clientCommand != null && clientCommand.isSigned() && clientCommand.getSeenMessages() != null) {
                if (con.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_19_3) {
                    con.getServer().unsafe().sendPacket(new net.md_5.bungee.protocol.packet.ClientChatAcknowledgement(clientCommand.getSeenMessages().getOffset()));
                }
                // Waterfall end
            }
        }
        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(TabCompleteRequest tabComplete) throws Exception
    {
        // Waterfall start - tab limiter
        if ( bungee.getConfig().getTabThrottle() > 0 &&
                ( con.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_13
                && !bungee.getConfig().isDisableModernTabLimiter()))
        {
            long now = System.currentTimeMillis();
            if ( lastTabCompletion > 0 && (now - lastTabCompletion) <= bungee.getConfig().getTabThrottle() )
            {
                throw CancelSendSignal.INSTANCE;
            }
            lastTabCompletion = now;
        }

        // Waterfall end - tab limiter
        List<String> suggestions = new ArrayList<>();
        boolean isRegisteredCommand = false;
        boolean isCommand = tabComplete.getCursor().startsWith( "/" );

        if ( isCommand )
        {
            isRegisteredCommand = bungee.getPluginManager().dispatchCommand( con, tabComplete.getCursor().substring( 1 ), suggestions );
        }

        TabCompleteEvent tabCompleteEvent = new TabCompleteEvent( con, con.getServer(), tabComplete.getCursor(), suggestions );
        bungee.getPluginManager().callEvent( tabCompleteEvent );

        if ( tabCompleteEvent.isCancelled() )
        {
            throw CancelSendSignal.INSTANCE;
        }

        List<String> results = tabCompleteEvent.getSuggestions();
        if ( !results.isEmpty() )
        {
            // Unclear how to handle 1.13 commands at this point. Because we don't inject into the command packets we are unlikely to get this far unless
            // Bungee plugins are adding results for commands they don't own anyway
            if ( con.getPendingConnection().getVersion() < ProtocolConstants.MINECRAFT_1_13 )
            {
                con.unsafe().sendPacket( new TabCompleteResponse( results ) );
            } else
            {
                int start = tabComplete.getCursor().lastIndexOf( ' ' ) + 1;
                int end = tabComplete.getCursor().length();
                StringRange range = StringRange.between( start, end );

                List<Suggestion> brigadier = new LinkedList<>();
                for ( String s : results )
                {
                    brigadier.add( new Suggestion( range, s ) );
                }

                con.unsafe().sendPacket( new TabCompleteResponse( tabComplete.getTransactionId(), new Suggestions( range, brigadier ) ) );
            }
            throw CancelSendSignal.INSTANCE;
        }

        // Don't forward tab completions if the command is a registered bungee command
        if ( isRegisteredCommand )
        {
            throw CancelSendSignal.INSTANCE;
        }

        if ( isCommand && con.getPendingConnection().getVersion() < ProtocolConstants.MINECRAFT_1_13 )
        {
            int lastSpace = tabComplete.getCursor().lastIndexOf( ' ' );
            if ( lastSpace == -1 )
            {
                con.setLastCommandTabbed( tabComplete.getCursor().substring( 1 ) );
            }
        }
    }

    @Override
    public void handle(ClientSettings settings) throws Exception
    {
        con.setSettings( settings );

        SettingsChangedEvent settingsEvent = new SettingsChangedEvent( con );
        bungee.getPluginManager().callEvent( settingsEvent );
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception
    {
        if ( pluginMessage.getTag().equals( "BungeeCord" ) )
        {
            throw CancelSendSignal.INSTANCE;
        }

        if ( BungeeCord.getInstance().config.isForgeSupport() )
        {
            // Hack around Forge race conditions
            if ( pluginMessage.getTag().equals( "FML" ) && pluginMessage.getStream().readUnsignedByte() == 1 )
            {
                throw CancelSendSignal.INSTANCE;
            }

            // We handle forge handshake messages if forge support is enabled.
            if ( pluginMessage.getTag().equals( ForgeConstants.FML_HANDSHAKE_TAG ) )
            {
                // Let our forge client handler deal with this packet.
                con.getForgeClientHandler().handle( pluginMessage );
                throw CancelSendSignal.INSTANCE;
            }

            if ( con.getServer() != null && !con.getServer().isForgeServer() && pluginMessage.getData().length > Short.MAX_VALUE )
            {
                // Drop the packet if the server is not a Forge server and the message was > 32kiB (as suggested by @jk-5)
                // Do this AFTER the mod list, so we get that even if the intial server isn't modded.
                throw CancelSendSignal.INSTANCE;
            }
        }

        PluginMessageEvent event = new PluginMessageEvent( con, con.getServer(), pluginMessage.getTag(), pluginMessage.getData().clone() );
        if ( bungee.getPluginManager().callEvent( event ).isCancelled() )
        {
            throw CancelSendSignal.INSTANCE;
        }

        con.getPendingConnection().relayMessage( pluginMessage );
    }

    @Override
    public void handle(LoginAcknowledged loginAcknowledged) throws Exception
    {
        configureServer();
    }

    @Override
    public void handle(StartConfiguration startConfiguration) throws Exception
    {
        configureServer();
    }

    private void configureServer()
    {
        ChannelWrapper ch = con.getServer().getCh();
        if ( ch.getDecodeProtocol() == Protocol.LOGIN )
        {
            ch.setDecodeProtocol( Protocol.CONFIGURATION );
            ch.write( new LoginAcknowledged() );
            ch.setEncodeProtocol( Protocol.CONFIGURATION );

            con.getServer().sendQueuedPackets();

            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public void handle(FinishConfiguration finishConfiguration) throws Exception
    {
        con.sendQueuedPackets();
    }

    @Override
    public void handle(CookieResponse cookieResponse) throws Exception
    {
        con.getPendingConnection().handle( cookieResponse );
    }

    @Override
    public String toString()
    {
        return "[" + con.getAddress() + "|" + con.getName() + "] -> UpstreamBridge";
    }
}
