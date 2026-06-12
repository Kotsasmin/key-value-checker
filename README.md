# KeyValueChecker

KeyValueChecker is a lightweight, anti-cheat Minecraft server plugin designed to detect specific client-side mods (like Meteor Client, Freecam, AutoTotem) that are otherwise invisible to the server.

## How it Works (Technical Details)

The plugin exploits the way the Minecraft client handles **Translation Components** on signs. It works entirely via packets using the `PacketEvents` library to bypass Bukkit serialization bugs (which otherwise cause "void future" disconnects on Paper 1.21+).

1. **Fake Sign Injection:** When a player joins, the server sends a block update packet creating a fake sign at `Y = 0`.
2. **Translation Payload:** The server sends an `OpenSignEditor` packet for that sign. The text lines of the sign are set to JSON translation components containing the specific language keys injected by disallowed mods (e.g., `{"translate":"key.freecam.toggle"}`).
3. **Client-Side Resolution:**
   - If the player **has the mod installed**, their client's language map will successfully translate the key into a localized string (e.g., "Freecam").
   - If the player **does not have the mod**, the client fails to translate it and falls back to the raw string (e.g., "key.freecam.toggle").
4. **Packet Interception:** When the sign editor is forcibly closed, the client sends an `UpdateSign` packet back to the server containing the text it evaluated. 
5. **Detection Validation:** The plugin intercepts the `UpdateSign` packet. If the received text does *not* match the raw translation key, the server mathematically proves the client has the mod installed.
6. **Batch Processing:** To remain performant, the plugin processes translation keys in batches of 4 (since signs have exactly 4 lines), re-sending packets until all configured keys are verified.
