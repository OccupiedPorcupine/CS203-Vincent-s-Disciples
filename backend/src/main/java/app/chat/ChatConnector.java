package app.chat;

// One chat app, such as the Telegram bot (#100). A channel can be linked only when its connector
// is a bean, which it is only when the channel is configured on this server.
public interface ChatConnector {

    ChatChannel channel();

    // Opening this link on the owner's phone starts the chat and sends the code, e.g.
    // https://t.me/ChickyBot?start=<code>.
    String linkFor(String code);
}
