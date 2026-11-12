# Group Chat — Discord-style group chats inside Minecraft

A Fabric mod for **Minecraft 1.21.11** that adds private group conversations: create groups,
invite players, chat in a dedicated GUI, send images and stickers, and hop on a voice channel
— all over the normal Minecraft connection, so there are no extra ports, servers or accounts.

*Made by Mayank · Afkz Studio.*

Everything runs **server-authoritative**: the server (or the integrated server in single player)
owns groups, membership, invites, history and images.

![The chat hub](docs/screenshot-chat.png)

## Features

| | |
| --- | --- |
| **Groups** | up to 16 members per group, multiple groups per player, owner can remove members |
| **Invites** | invite anyone online, invites show up in-game with Accept / Decline |
| **Chat GUI** | `G` opens a two-pane hub: group list, unread badges, message bubbles, timestamps, scrolling |
| **Images** | send PNG/JPG/GIF/BMP — drag & drop onto the window, the built-in image browser, or copy them into `config/groupchat/images` |
| **GIFs** | a **Gif** button opens a Tenor-powered picker: search, preview, click to send — the animation plays in the chat |
| **Stickers** | bundled pack (`smile`, `laugh`, `sad`, `cool`, `heart`, `star`, `creeper`, `diamond`, `party`) |
| **Share coordinates** | one **Coords** button posts your position + dimension; **click the message to turn it into a waypoint** |
| **Waypoints** | drawn **in the world**: coloured marker box, a 64-block light column and a name+distance tag; `B` manages them |
| **Voice calls** | push-to-talk voice channels per group, mixed so several people can talk at once |
| **Notifications** | new messages are echoed to the chat HUD with a sound when the hub is closed |
| **Mod Menu** | the settings screen is also reachable from **Mods → Group Chat → Config** (Mod Menu is optional) |
| **Persistence** | groups + history are stored in the world folder, images on disk, all survive restarts |
| **Commands** | everything the GUI does is also available as `/gchat ...` |

## Install

Requires **Minecraft 1.21.11**, **Fabric Loader 0.20.1+** and **Fabric API 0.141.6+1.21.11**
(or newer). Java 21.

1. Drop `groupchat-1.0.0.jar` into `.minecraft/mods`.
2. Do the same on the server (the mod must be on the server; clients without it can still play,
   they just don't see the chat).

Grab the build with:

```bash
./gradlew build          # -> build/libs/groupchat-1.0.0.jar
```

### Other Minecraft versions

The mod is also ported to the 26.x line. Every version has the same features, including the GIF
picker, the settings screen, the key bindings and the two optional integrations (Mod Menu and
Simple Voice Chat). The 26.x builds need **Java 25**.

| Minecraft | Folder | Build |
| --- | --- | --- |
| 1.21.11 | `.` (the main project) | `./gradlew build` → `groupchat-1.0.0.jar` |
| 26.1 | `versions/26.1` | `gradlew build` → `groupchat-26.1-1.0.0.jar` |
| 26.1.1 | `versions/26.1.1` | `gradlew build` → `groupchat-26.1.1-1.0.0.jar` |
| 26.1.2 | `versions/26.1.2` | `gradlew build` → `groupchat-26.1.2-1.0.0.jar` |
| 26.2 | `versions/26.2` | `gradlew build` → `groupchat-26.2-1.0.0.jar` |
| 26.3 | `versions/26.3` | `gradlew build` → `groupchat-26.3-1.0.0.jar` |

The ports share `versions/common.gradle`. Because the 26.x game API differs from 1.21.11 in a
handful of places, the sources are kept in sync by a small script instead of by hand:

```bash
# copies the current features into a port and rewrites the 26.x API calls
powershell -File versions/port.ps1 -Target 26.3 -NewAccessors
powershell -File versions/entrypoints.ps1 -Target 26.3
```

`-NewAccessors` is only needed for 26.2 and 26.3, which moved the chat and the screen accessor.

## Using it

| Action | How |
| --- | --- |
| Open the hub | `V` (rebindable: Options → Controls → Group Chat) |
| Create a group | `+ New group` in the sidebar → name it → tick players → `Create` |
| Invite someone | `Members` → type a name → `Invite`, or `/gchat invite <player> <group>` |
| Accept an invite | `Invites (n)` in the sidebar → `Accept` |
| Send a message | type in the box, `Enter` |
| Send an image | `Img` (browser with thumbnails, opens on `screenshots`), paste a folder/file path into its address box, or **drag & drop** a file on the window |
| Send a GIF | `Gif` — a Tenor search box with live previews; click one to send it |
| Send a sticker | `Stk` |
| Share coordinates | `Coords` — posts your position to the group |
| Share my position | `M` — posts your spot to the active group **and** pins it as a waypoint right away, without opening the chat |
| Create a waypoint | **click a shared-coordinate message** in chat |
| Waypoint list | `B` |
| Settings | `O`, or **Mods → Group Chat → Config** when Mod Menu is installed |
| Voice | `Start call` / `Join call` in the header, then **hold `N`** to talk |
| Mute / leave | `Leave call` in the header (also mutes the mic for others) |
| Switch group | `Tab`, or click in the sidebar |
| Close / back | `Esc` (once per panel) |

Messages written with `/gchat msg <group> <text>` are relayed like normal ones, so you can chat
without opening the GUI.

![Members panel](docs/screenshot-members.png)

### GIFs

The **Gif** button next to the composer opens a search screen. Type a word, press `Search`, and the
results come back as thumbnails; clicking one sends it to the group, where it plays back as an
animation.

No sign-up is needed: without an API key the picker reads the public `tenor.com` search page.
Tenor stopped handing out new API keys in January 2026, so if you *do* have one you can paste it
into **Mod Menu → Group Chat settings → GIF search key** (an optional key makes searches faster and
more reliable). Both `tenor.googleapis.com` and Giphy's Tenor-compatible `api.giphy.com` work.

GIFs are stored next to the images as `<imageId>.gif` and are protected by the same rule: only
members of the owning group can download them.

### Mod Menu

[Mod Menu](https://modrinth.com/mod/modmenu) is optional, but when it is installed the client
settings show up in the mod list: **Mods → Group Chat → Config** opens the very same screen the
mod opens itself with `O`. The screen is a plain vanilla options screen — title on top, the
settings as ordinary cycling buttons in the middle, **Done** at the bottom — so it reads and
behaves exactly like the rest of the game. Hovering a setting explains what it does.

Without Mod Menu nothing changes: the mod keeps working and the settings stay reachable with `O`.

![Mod Menu showing the Group Chat entry](docs/screenshot-modmenu.png)

![The Group Chat settings](docs/screenshot-config.png)

### Key bindings

All of them live in the vanilla **Options → Controls → Key Binds → Group Chat** category, so they
can be rebound like any other key:

| Key | Action |
| --- | --- |
| `V` | open / close the chat hub |
| `N` | hold to talk in a voice call |
| `B` | open the waypoint list |
| `O` | open the settings screen |
| `M` | share the spot you are standing on with the active group and pin it as a waypoint |

![The Group Chat key bindings](docs/screenshot-keybinds.png)

### Sharing coordinates → waypoints

1. Stand somewhere interesting and press **`Coords`** in the composer. The group instantly gets a
   message like `141, -60, -179 · Overworld · 580 m away`.
2. Anyone in the group **clicks that message** and a waypoint appears in their own list.
3. The waypoint is rendered **in the world** at that exact spot: a coloured marker box, a light
   column you can see from far away, and a name + distance tag when you are within 96 m. The `B`
   screen lists them (hide / delete / add the spot you're standing on).

![A second player's view of shared coordinates, a fetched screenshot and the waypoint bubble](docs/screenshot-two-players.png)

![Waypoint list](docs/screenshot-waypoints.png)
![A waypoint rendered in the world](docs/screenshot-waypoint-world.png)

Waypoints are per-client: the server only relays the coordinate message, so each player decides
what to keep. Clicking the same message twice does nothing (duplicates within 2 blocks are ignored).

### Voice chat details

* 16 kHz mono, 40 ms frames, IMA-ADPCM 4:1 → about **8 KB/s per speaker**.
* Frames are relayed by the server only to members that joined the call.
* Push-to-talk with a noise gate; the header shows `muted` / `talking` / `hold N to talk`.
* Playback mixes every speaker into a single output line, so overlapping speech works.
* If no microphone or speakers are available the call still joins (listen-only) and the reason is
  logged; the client never leaves the mic open after being kicked or leaving the call.

## Commands

```
/gchat list                       your groups and pending invites
/gchat create <name>              create a group
/gchat invite <player> <group>    invite a player (group id or name)
/gchat accept <group>             accept an invite
/gchat decline <group>            decline an invite
/gchat leave <group>              leave (group is deleted when the last member leaves)
/gchat msg <group> <text...>      send a message without opening the GUI
/gchat call <group>               join the group's voice channel
/gchat hangup <group>             leave the voice channel
```

## Limits & storage

Client waypoints: `config/groupchat/waypoints.json`.

Server: `<world>/groupchat/groups.json` (groups, members, invites, last 200 messages per group),
`<world>/groupchat/images/<groupId>/<imageId>.png` (max 96 images per group; the oldest are
evicted) and `<world>/groupchat/images/<groupId>/<imageId>.gif` (max 24 GIFs per group, kept
separately so they do not evict images). Client: nothing is written outside
`config/groupchat/images` (a drop folder).

| Limit | Value |
| --- | --- |
| members per group | 16 |
| message length | 900 characters |
| history kept per group | 200 messages |
| image size after conversion | 1.5 MB, scaled to max 1280 px |
| GIF size | 3 MB, scaled to max 200 px, 16 frames kept |
| rate limits | 40 msgs / 10 s, 20 images / min, 10 GIFs / min, 30 invites / min, 3 groups / min, 2× frame rate for voice |

All of them live in `com.example.groupchat.Constants` if you want to tune them.

## How it works

```
client                        server (or integrated server)
  HubScreen ── C2S payload ──► ServerNet ── GroupManager ── groups.json
      ▲                            │            │
      └──── S2C payload ◄──────────┴────────────┴── ImageStore (PNG on disk)
                                   │
                          Voice relay (call members only)
```

* **Coordinate sharing** — `SendLocation` (C2S) is validated and clamped server-side, then stored as
  a normal message with kind `KIND_LOCATION` whose text is `dimension|x|y|z`, so coordinate messages
  survive history replay without any extra protocol state.
* **Transport** — one Fabric networking channel. Payload records live in `net/Payloads.java`,
  registered in `net/Protocol.java`. Image payloads use `registerLarge` so a 1.5 MB PNG is split
  into 30 KB packets automatically.
* **Server** — `GroupManager` (logic + JSON persistence), `ImageStore` (PNG validation, eviction),
  `ServerNet` (payload handlers + rate limits), `GcCommands` (Brigadier tree).
* **Client** — `ClientState` (mirror of the snapshot), `HubScreen` (all panels),
  `ImagePickerScreen` / `StickerPickerScreen`, `TextureCache` (image id → GPU texture, LRU),
  `VoiceClient` + `Adpcm` (capture, codec, jitter buffer, mixer).
* **Authority** — every mutation is validated server-side (membership, ownership, rate limits,
  payload sizes, PNG magic bytes). Clients cannot send to groups they are not in, and images can
  only be fetched by members of the owning group.

## Development

```bash
./gradlew build         # compile + package (jar + sources jar)
./gradlew runClient     # dev client
./gradlew runServer     # dev server (accept run/eula.txt on first run)
./gradlew genSources    # decompile Minecraft for navigation
```

### Scripted self-tests

The mod ships a dev-only harness (only active with `-Dgroupchat.devtools=true`) that is used to
verify the GUI and the protocol without clicking around:

```bash
# UI tour: seeds demo groups/messages/images, screenshots every panel -> run/groupchat-dev-N.png
./gradlew runClient -PgcDevtools=true

# Voice codec + audio device check (logs ADPCM round-trip SNR)
./gradlew runClient -PgcDevtools=true -PgcVoiceTest=true

# Live protocol test: joins a server, creates a group, sends text + sticker + image,
# screenshots the result -> run/groupchat-dev-9.png
./gradlew runServer                      # in one terminal (online-mode=false)
./gradlew runClient -PgcDevtools=true -PgcConnect=127.0.0.1:25565

# Two scripted players in one dedicated server (see run-agents/*.bat):
#   agent A: joins, teleports away, creates "expedition", invites B, chats, shares coords,
#            uploads a real in-game screenshot
#   agent B: accepts the invite, clicks A's coordinates into a waypoint, replies with its own
#            coordinates, screenshots the waypoint HUD
./gradlew runClient -PgcDevtools=true -PgcAgent=a -PgcConnect=127.0.0.1:25565
./gradlew runClient -PgcDevtools=true -PgcAgent=b -PgcConnect=127.0.0.1:25565

# Mod Menu check: opens the mod list, checks that Mod Menu knows the settings screen,
# opens it and the vanilla key binds screen -> run/agent-modmenu-*.png
./gradlew runClient -PgcDevtools=true -PgcAgent=modmenu
```

Verified with those: hub rendering, image scaling, sticker rendering, group creation, text and
sticker relay, image upload (large-payload splitting), server-side persistence, ADPCM round trip at
~22 dB SNR, and a full two-client session (A → server → B) for chat, coordinate sharing, waypoint
creation and cross-client image download.

## Known limitations

* Voice rides Minecraft's TCP connection. Latency is fine for chatting but it is not a
  UDP voice mod — a side-port transport is the natural next step.
* No moderation tooling beyond kick/leave, no mute lists, no message deletion or editing.
* History is capped at 200 messages per group (oldest are dropped, including image references).
* Stickers are the bundled pack; custom stickers are sent as regular images.
* Voice volume is fixed (per-member sliders are on the roadmap).
* Waypoints are per-player (each player creates their own from the coordinate message); there is no
  minimap, and markers are hidden for waypoints in other dimensions.
* The server operator can read stored messages and images — same as any server-side chat mod.

## License

MIT © 2026 Mayank (Afkz Studio). See [LICENSE](LICENSE).
