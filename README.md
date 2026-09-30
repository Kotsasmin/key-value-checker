# KeyValueChecker

KeyValueChecker is a lightweight, anti-cheat Minecraft server plugin designed to detect specific client-side mods (like Meteor Client, Freecam, AutoTotem) that are otherwise invisible to the server.

## History

So here is my journey of how I found out about this. Well, someone would say, exploit of Minecraft.

I was trying to figure out how on earth DonutSMP could display the (now I know) Paper Adventure emojis in their dialogue system by inspecting each and every packet my client sent to the server. I had set up a proxy for that. As I was doing it, I saw many signs being placed around me with names like `key.freecam.toggle`, `key.category.voicechat.voicechat`, and such.

After some research, I figured out about the exploit and thought of it as a really clever technique to finally get rid of most cheating players on a server that I was about to open.

Well, after a couple of weeks of work, I made the plugin a reality and it worked almost flawlessly. I also had my dearest friend ToxicManiax help me discover the most well-known translation keys found in commonly used cheat clients or mods.

Here it is out in the public now.

I don't, of course, promote any kind of illegal activity or breaking the Minecraft EULA in any way. Quite the opposite, actually. I want everyone to play fair. I really despised the fact that 90% of the people who initially tried to enter my server had cheats on, and I didn't even know it was that bad. I want everyone to play fair, and to make those script kiddies with paid clients and cheating mods rethink what they are doing.

Of course, if you know of any clients like this and want to contribute to the project, you might as well open an issue stating a few flags/keys that I should add by default. I know of some clients (I might have added some by now, like Crypton for instance), but I don't have all their translation keys.

If you like the project, feel free to give it a star! If you want to contribute, contact me on Discord or simply open an issue. I really enjoyed building this for everyone. I wish you all a happy and fair day! Love ya all!

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
