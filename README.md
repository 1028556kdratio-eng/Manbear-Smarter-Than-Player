# Manbear – Forge 1.20.1 horror mod

## Update 45 — Manipulation Director and staged mind games

This update adds a coordinated encounter planner on top of the existing independent-mind system. Events are rare, conditional, saved with the Manbear, and recorded as player-specific episodes so the next encounter can change based on what the player did.

* **False weakness:** while visible and watched, he may sit motionless and appear vulnerable. If the player approaches closely, he tries to retreat toward real cover. The event is a feint, not an actual injury state.
* **Baited reward:** he may reposition an existing dropped item, or release a still-pending trophy previously stolen from this same player, at a discoverable location. It never clones an item stack. The Manbear observes whether the player investigates or retrieves it.
* **Divided attention:** two spatially separated sound cues can be staged close enough to hear. He observes which one the player looks toward or moves toward, and records that preference.
* **Hidden objective:** a sound cue near the player's known Overworld base appears to be the event's purpose, while the Manbear's real objective is to observe the player's alternate route or exit choice.
* **Hidden-answer test:** two competing footprint trails lead in different directions. He records which explanation the player follows and revises route beliefs gradually.
* **Manipulation Director:** the event planner uses per-player cooldowns and branching outcomes instead of independently rolling every trick. Events can fail or be ignored; those outcomes are saved as episodes.
* **Learned defenses as tools:** the existing learned-object behavior now scores previously inspected mechanisms. Levers connected to nearby redstone components and mechanisms near a known base route are more interesting candidates. After manipulating a mechanism, he records whether it was part of a known circuit or a standalone mechanism.

### Update 45 configuration

The new options are in `config/manbear-common.toml`:

* `manipulationDirectorEnabled` — master switch (default `true`).
* `falseWeaknessChance` — default `0.035` per eligible decision.
* `baitedRewardChance` — default `0.045` per eligible decision.
* `dividedAttentionChance` — default `0.035` per eligible decision.
* `hiddenObjectiveChance` — default `0.03` per eligible decision.
* `hiddenAnswerTestChance` — default `0.03` per eligible decision.
* `manipulationCooldownTicks` — default `18000` ticks (15 in-game minutes) between new manipulation events for the same player.

The event chance values are checked only when their context is eligible, and the global cooldown prevents the director from repeatedly staging encounters. Bait uses an existing world item or a pending trophy belonging to the target player; it does not read the player's inventory or force-load chunks. Divided-attention reactions are inferred from visible gaze and movement, not from hidden player intent.

## Update 43: Update 42 compilation fixes
- Fixed the `BlockPos.distSqr` result type in the trap-observation path (`double`, not `long`).
- Read saved position entries from `ListTag` by casting the long tags to `LongTag` and calling `getAsLong()`, matching Minecraft 1.20.1 mappings.
- Added the missing `Mob` import for persistent kidnapped pets.

Requires: Minecraft 1.20.1, Forge 47.x, **GeckoLib 4.4.9+** (4.x).

## Build
1. Install JDK 17.
2. Get a Gradle wrapper: copy `gradlew`, `gradlew.bat` and the `gradle/` folder from the official
   Forge 1.20.1 MDK into this folder (or run `gradle wrapper --gradle-version 8.1.1` if you have Gradle).
3. `./gradlew build` -> jar in `build/libs/`.  `./gradlew runClient` to test.
4. Drop the jar + GeckoLib into `mods/`.

## Your assets
`geo/manbear.geo.json`, `textures/entity/manbear.png` and `manbear_eyes.png` are byte-for-byte your files.
`animations/manbear.animation.json` is your file plus two derived clips: `crouch_walk` (walk cycle in the
hunt posture) and `crawl` (slowed `run2` gait), and `walk` is set to loop. Nothing in the textures was touched.
Model scale is `ManbearRenderer.MODEL_SCALE` (0.7 -> ~3 blocks tall, matching the 5-block hitbox).

## Testing quickly (OP)
* Spawn egg in the creative tab, or `/manbear spawn`
* `/manbear mode stalk|hide|peek|fake_chase|chase|lay_trap|ambush|retreat`
* `/manbear spawn [chaser|faker|trapper|sleeper]` forces that kind of spawn (also `behind`, `lurer`, `path`, `fog`, `cave`, `lurker`, `petnapper`, `lightning`, `intimidation`)
* `/manbear info`, `/manbear profile` (what he has learned about you), `/manbear remove`, `/manbear reset`
* Config: `config/manbear-common.toml` (spawn odds, speeds, damage, griefing, ...)

## How the requested features are done
| Request | Where |
|---|---|
| Spawn 1/25 per minute, only one per world | `ServerEvents.spawnRoll`, `ManbearData.claim` (a second one discards itself) |
| Stalking, circling while facing you | `ManbearBrain.tickStalk` + `ManbearMoveControl` (strafe input relative to a body that always faces you) |
| Peek behind trees, hide behind bushes | `CoverFinder` (logs/leaves/terrain that break line of sight, plus a side spot to lean out), `tickHide`, `tickPeek` |
| Random mode switching (stalk -> peek -> fake -> real chase) | `ManbearBrain.spontaneous` / `pickNextMode` – weighted, checked every second |
| Fake chase | `tickFake` (charges, stops short, stares; ~10% turn out real) |
| Real chase, lunge, speed = player then slightly faster | `tickChase`, `tickLunge`, `refSpeedMod` (matches your actual sprint speed, up to +12% over 45 s) |
| Break blocks to reach you | `BlockBreaker` (respects mobGriefing + config) |
| Climb / crawl | `WallClimberNavigation` + climb flag; stance system (stand 5.0 / crouch 3.3 / crawl 0.9) picks a smaller body when a route needs it |
| Crouch to be stealthy | stance + `hunt` / `crouch_walk` animations |
| Bear traps, ambush when you step in one | `BearTrapBlock` (hunter_set) -> `ManbearEntity.onTrapSprung` -> `AMBUSH` mode |
| Directional bear noises | positional sound events (`ModSounds`) played from his body |
| Glowing eyes | `ManbearEyesLayer` (your eyes texture, full-bright). The texture only has 2 pixels, so the glow is small. |
| Adapts to the player | `PlayerProfile` (vigilance, panic, aggression, camping, sneak, sprint, base location) saved in the world |

### What adaptation does
Looks at him a lot -> hides/peeks/fakes more and freezes when stared at. Ignores him -> comes closer, chases sooner.
Runs when chased -> more real chases and lunges. Fights back -> more traps, hit-and-run. Camps/holes up -> more chases
with block breaking, traps near your base.

### Sounds
They alias vanilla polar-bear/ravager sounds pitched down (`assets/manbear/sounds.json`). To use your own, put `.ogg`
files in `assets/manbear/sounds/` and point the `sounds.json` entries at them.

## If it does not compile first time
This was written without access to the Forge/GeckoLib jars. The spots most likely to need a tweak if your GeckoLib
version differs slightly: `withScale` and `reRender` (renderer/eyes layer), `AnimationState.isMoving`. The brain, entity and
block code only use vanilla 1.20.1 (official mappings) APIs.

## Update 2: stealth, short appearances, peeking, animals, ambience
* **Stalking is farther away** (default 26-42 blocks, `stalkMinDistance` / `stalkMaxDistance`); hiding/peeking cover is searched at that range too. He only closes in when ambushing, chasing, fake chasing (or if you walk up to him).
* **He vanishes after 90-120 s** (`lifetimeMinSeconds` / `lifetimeMaxSeconds`). If you are not looking at him he disappears immediately; if you are, he backs away and fades. If a chase is running he finishes it first (max +40 s). Bear traps he laid stay in the world (capped by `maxTraps`).
  Because his life is short, the real-chase grace period is now 20 s and he switches modes more often.
* **Peeking** now stays behind the tree and *leans out* around the trunk with the new `peek_left` / `peek_right` animations (built from your `hunt` pose; legs planted, hips/torso/head swing out, one arm grips the tree).
  If he leans into the tree instead of out of it, flip `swapPeekSides` in the config.
* **Animals** panic and run when he is near, and scatter in random directions for the first ~12 s after he appears (`animalsFlee`, `animalFleeRange`).
* **Distorted pig / cow / sheep noises** (`mimicAnimalSounds`) come from him, or sometimes from behind you.

## Update 3: scarier chases
* **Only 1 in 10 spawns are chasers** (`chaseChanceDenominator`). The other 9 never start a real chase on their own: they stalk, hide, peek, fake-charge and lay traps (fake charges become more common). A chaser can still be provoked into attacking if you hit him, and a bear trap you step in still triggers an ambush.
* **Faster chases:** he starts 15% faster than your sprint (`chaseSpeedBonusBase`) and builds to 35% faster after 30 s (`chaseSpeedBonusMax`, `chaseRampSeconds`). Fake charges run 15% faster than you so they look real.
* Delete `config/manbear-common.toml` (or edit the values) so the new defaults apply.

## Update 4
* **Spawn kinds.** Every spawn rolls independently: 1/14 chaser (`chaseChanceDenominator`), 1/9 fake-chaser (`fakeChaseChanceDenominator`), 1/6 trap-layer (`trapChanceDenominator`), 1/13 sleeper (`sleeperChanceDenominator`).
  A spawn with none of those only stalks, hides and peeks. (Getting hit still provokes him, and a trap you step in still brings him.)
* **Sleeper.** Found slumped on the ground with his eyes dark, snoring (you can hear him). Vibrations wake him like a Warden: sprinting/walking nearby, landing jumps, breaking/placing blocks, explosions, projectiles. Sneaking is silent. Wakes -> roars and chases at once. Sleepers last 4-6 min (`sleeper*` settings). `sleepNoiseThreshold` tunes how easily he wakes.
* **Sound design.** Heavy double-layered footsteps whose volume rises steeply as he gets closer, deeper breathing when near, layered deep roars. The roars/footsteps are built from pitched-down vanilla bear/ravager/warden sounds. To use real recordings, drop `.ogg` files into `assets/manbear/sounds/` and point the entries in `sounds.json` at them (e.g. `{"name":"manbear:roar1"}`).
* **Peeking** keeps the leaning pose until he starts moving again (one long lean per peek instead of in-and-out).
* **Stun roar** during real chases: only when you are 16-50 blocks away with line of sight, max 2 per chase, 2 s freeze + camera forced onto him. He keeps running (12% faster) while you are frozen. Settings: `roarsPerChase`, `roarMinDistance`, `roarMaxDistance`, `roarStunTicks`.
* Delete `config/manbear-common.toml` so the new options and defaults are created.

## Update 5
* **"Behind you" event (rarest, 1/20 spawns, `behindChanceDenominator`).** He appears 11-16 blocks directly behind you and stands motionless and silent (you may hear him breathe). He quietly follows if you walk off. When you finally turn and see him he roars (very loud, also played in your ears) and charges at 5x your sprint speed (`behindRushMultiplier`) for the first ~2.5 s, then chases normally. Test: `/manbear spawn behind`.
* **Roar limit fixed.** The 2-per-chase limit used to reset whenever he re-entered chase mode. It now resets only after 30 s without any chase, so he can never roar more than `roarsPerChase` times in one engagement.
* **Peek animation** now runs on its own animation layer (`peek`), independent of the walk/idle logic, and is re-asserted every tick while he holds the lean.
* **Sleep pose** rebuilt: lying flat on his stomach, arms and legs sprawled, head turned with cheek on the ground, slow breathing. (Built on the same forward-pitch pose as your `run2`; the lying height/offset may need a small nudge once you see it: edit `Manbear.position` in the `sleep` animation.)
* **Tunnelling to you in a house.** When he cannot path to you he now walks straight toward you and breaks the first block in the line between you and him (walls, roof, floor), looking both low and high. The old version only checked the block right in front of him, which is why he stood outside.

## Update 6
* **Thick trunks (2x2, 3x3 jungle/dark-oak):** the cover finder now measures trunk width, uses only the back face, hides behind the MIDDLE of a wide trunk and searches further out for a peek spot. Wide trunks use the new `peek_left_wide` / `peek_right_wide` animations (a much deeper lean). Normal trunks are unchanged.
* **Behind-you pounce:** when you turn and see him he now does a real leap: a short coil, then a ballistic jump that lands on you in under half a second (leading a moving target), with a new `leap` animation (coil -> full stretch, jaw open), the loud roar, and then the 5x-speed run if he missed.
* **Swimming:** in deep water he swims straight at you, faster than you swim (`swimSpeedFactor`), with a new `swim` animation and a flatter swim hitbox. Shallow water he wades through. Swimming away no longer works.
* **Animal lure (1/6 spawns, `lureChanceDenominator`):** he slips into cover, a wolf appears between you and him (whining to get your attention). Once you see it, it stares at you for a moment, then sprints to him. When it reaches him he lets out a long roar (about 4 s) and starts a real chase if that spawn is a chaser, otherwise a fake chase. Test: `/manbear spawn lurer`.
* Delete `config/manbear-common.toml` to get the new options.

## Update 7
* **Lure animals:** the lure event now picks a random wolf, pig, sheep or cow (each calls with its own sound to get your attention).
* **Lure outcome (`lureTrapChance`, default 0.7):** 70% the animal runs to a bait spot a few blocks in front of his hiding place and waits there calling you in; the spot is ringed with his bear traps on your side. Step on one and the usual trap ambush starts. If nobody comes for ~45 s he gives up quietly. 30% it runs straight to him, he lets out the long roar and a chase (or fake chase) begins. His traps only catch players, so the animal can run over them. Test: `/manbear spawn lurer`.
* **Rivers:** he now wades/swims through any real water (even 1 block deep), and if there is no walkable path to you (and nothing to break) he charges straight at you instead of giving up. Crossing a river no longer saves you. (He will not charge straight into lava.)
* **Stalking sounds (`ambientTricks`, `trickMinIntervalTicks`, `trickMaxIntervalTicks`):** while he stalks/hides/peeks he occasionally plays a chest opening and closing, a door (wood or iron) opening and closing, a barrel, a torch fizzling out, or a low grumble, from random spots 7-18 blocks around you (often behind you).
* **Stealthier stalking:** he spends much less time walking around in the open. Stalk phases are shorter, hiding/peeking are much more likely, he ducks behind the next tree quickly (after ~2 s of being stared at, or randomly), and when he finishes hiding he usually hops to a DIFFERENT tree (6+ blocks away) instead of staying put, so he moves tree-to-tree.
* Delete `config/manbear-common.toml` for the new options.

## Update 8: mostly stalking and hiding
* New gate `eventChance` (default 0.3): only 30% of spawns have any special event at all. The other **70% are pure stalkers** that only stalk, hide, peek and make ambient sounds, never chase.
* A spawn with an event gets exactly ONE of: real chase, fake chase, trap-laying, sleeping, behind-you, animal lure. The old `...ChanceDenominator` settings are now just their RELATIVE rarity (behind-you stays the rarest). Of all spawns that works out to roughly: trap-laying 8%, lure 8%, fake chase 5%, sleeper 4%, chase 3%, behind-you 2%, and 70% nothing.
* The lure ends in a real chase only 30% of the time (otherwise a fake chase, or the trap bait).
* If you hit him he now chases only 35% of the time (otherwise he mostly backs off), and a real chase can still be triggered by a bear trap you step in.
* Raise `eventChance` for more action; set it to 0 for a pure stalker mod.
* Delete `config/manbear-common.toml` for the new option.

## Update 9: path blocker, fog watcher, cave event, lunge-only traps
* **Trap ambush is now only a lunge.** Step in one of his traps and he rushes you and lunges once; the moment the lunge (or a swing) hurts you he vanishes in a puff of smoke. No chase, no roar-stun. If he can't reach you within ~25 s he vanishes anyway. (Same for the animal-lure trap bait.)
* **Path blocker** (a weighted event, `pathBlockerChanceDenominator`): he learns your most-visited spots (the profile now remembers exact locations: your door, your farm, a cave mouth...). He appears standing at one of them 30-40 blocks away (`pathBlockerMinDistance`/`MaxDistance`), not in your view, and just stands there. Come within a random 4-12 blocks and he does the fast leap and starts a real chase. If you have no hot spots yet (or none in range) the spawn is just an ordinary stalker. Test: `/manbear spawn path` (spend a few minutes in a place first).
* **Fog watcher** (1 in 10 of ALL spawns, `fogWatcherChanceDenominator`): he stands at the very edge of YOUR render distance, in the direction you're looking, where the fog is thickest, so he's just a dark shape with glowing eyes. He never chases; if you walk toward him he backs away to keep the distance, and if you rush him he slips away. Needs sky above you (not underground). Test: `/manbear spawn fog`.
* **Cave event** (`caveEventChance`, default 0.35 of spawn rolls that happen while you're in a cave): he sits in the open middle of a cave, back to you, in a spatter of redstone dust (blood). Come within `caveFleeDistance` (12) and he bolts away at ~2.4x your speed with a startled growl, and despawns once out of sight (or after 15 s). The "blood" stays behind. New `sit` animation. Test: `/manbear spawn cave` while underground.
* Delete `config/manbear-common.toml` for the new options.

* Fog watcher distance uses the server view distance (your render distance in singleplayer); `fogEdgeChunks` overrides it for servers.

## Update 10
* **Fake chase** now runs at 3x your sprint speed (`fakeChaseSpeedMultiplier`), tears through blocks and wades rivers to reach you, and after it he sprints off and despawns as soon as he is out of sight (unless the 10% "it was real" roll turns it into a chase).
* **Villagers** join wolf/pig/sheep/cow as lure animals.
* **Grab:** during a chase, when he catches you (a swing or a landed lunge) there is a `grabChance` (0.5) that he grabs you instead. He holds you in front of his chest, 1 damage per second (`grabDamagePerSecond`; armor reduces it), and the exp bar becomes the escape bar: mash SPACE to fill it (`strugglePerPress`, it drains slowly if you stop). After 12 s he throws you off; if you break free he is dazed for 1.5 s and cannot grab again for 15 s. Needs the mod on both client and server (a tiny key-press packet).
* **Lurker** event (`lurkerChanceDenominator`): he appears 5-8 blocks right behind you and keeps scrambling to stay at your back and out of sight as you turn. If you look straight at him for about 0.6 s he bolts away very fast and despawns when out of sight. Test: `/manbear spawn lurker`.
* **Home raid** (`homeRaids`, `raidChanceDenominator` = 15, `raidAwayDistance` = 150): for every minute you spend more than 150 blocks from your base there is a 1/15 chance a raid is queued. When the base chunks are already loaded, he can rummage while you are away; if they remain unloaded, it falls back to your return so you can still discover the aftermath. With trophy theft enabled, a few items are removed from chests/barrels but the containers remain intact; stolen items are recorded and later placed into a hidden trophy chest in his den. He can also leave doors open, disturb windows and walls, and create giant footprints. **Beds and anything touching a bed are never touched.** "Base" = the place you spend the most time. Test: `/manbear raid` (your base) or `/manbear raid here` (around you).
* Delete `config/manbear-common.toml` for the new options.

## Update 11
* Real chases now run at the same speed as fake chases: 3x your sprint speed (`chaseSpeedMultiplier`, default 3.0). The old gradual speed-up settings (`chaseSpeedBonusBase/Max`, `chaseRampSeconds`) are no longer used. Lower `chaseSpeedMultiplier` if that is too much.
* `manbear.animation.json` is now your updated file (new peeking, sleep, swim, sit and grab, plus your tweaks to walk, roar and leap). It contains every animation the mod uses.

## Update 12: phantom sounds and your own voice
* **Ambient phantom sounds** (`ambientSounds`, `ambientMinSeconds`/`ambientMaxSeconds`, default every 75-240 s, a bit more often at night): nothing spawns, you just hear it. Very distant bear roars/growls (sometimes answered closer), twigs snapping close by, a tree falling (cracks, rushing leaves, heavy crash), screams, animals crying out and dying. Underground you only get the roars and twigs. The sounds are built from pitched vanilla sounds (ghast scream, animal death sounds, wood/bamboo breaks...); you can swap in real recordings via `sounds.json`.
* **Voice mimicry, built into the mod** (no Simple Voice Chat needed): while enabled, the client records separate sayings from the local microphone in RAM. Each snippet is tagged with the utterance it came from. On a voice-mimic event, the client shuffles 2–4 fragments and guarantees that the collage includes snippets from at least two different sayings; it waits silently until two separate sayings have been captured.
* This is **audio splicing, not text-to-speech or voice cloning**: no transcript is generated and all sounds are taken from what was actually spoken into the microphone. The waveform-based boundaries are approximate, so a piece may contain part of a word, one word, or a couple of words. Clips are never written to disk and are cleared when leaving the world.
* Client config `manbear-client.toml`: `micSampling` controls local microphone capture; `micThreshold` filters background noise; `voicePitch` and `voiceVolume` affect playback. You can use `/manbearvoice sampling on|off` and `/manbearvoice status` in-game. If Java cannot open the microphone (permissions or no device), it logs a line and stays silent.

## Update 13: other players' voices (multiplayer)
* New: in multiplayer he can play **another player's** voice to you, so you hear your friend calling from the woods. 70% of voice triggers use someone else's phrase when any are available (otherwise your own).
* It is **opt-in per player**: sharing is OFF by default in a fresh client config. Each player can turn it on with `/manbearvoice share on` (off again with `/manbearvoice share off`; `/manbearvoice status` shows the state; or `shareVoiceWithServer` in `manbear-client.toml`). Only players who opt in have their audio sent to the server; everyone else just hears their own voice as before.
* Server side: phrases are squeezed to 8 kHz mu-law, kept in **server RAM only** (max 12 per player), never saved to disk, dropped when that player logs out or the server stops. Server owners can refuse them completely with `voiceSharing = false`. Please tell your players before turning it on.
* Because a phrase must be sent and relayed, shared voices sound a little more telephone-like than your own.

## Update 14
* Multiplayer voice sharing remains **OFF by default** for new client configs. Existing `manbear-client.toml` files retain their chosen value; use `/manbearvoice share off` if you do not want microphone audio sent to the server.

## Update 15: aggression, stealth, bushes, strict despawn
**Rage (aggression), per player, 0-100** (`rageEnabled`). Shown by `/manbear profile`; `/manbear rage` shows it and `/manbear rage set <0-100>` sets it for testing. It cools off by 1 per minute (`rageDecayPerMinute`) and is raised by:
* cutting logs of any tree, vanilla or modded (any block in the logs tag, or named *_log / *_stem / *_hyphae / *_wood): +0.8 each (`rageLog`)
* getting within 12 blocks of him while he stalks, hides or peeks (+2 per second, double within 6) (`rageCloseToHim`)
* killing harmless creatures that give you nothing: rabbits, wolves, llamas, villagers, wandering traders, iron golems, bats, foxes, cats, ocelots, parrots, axolotls, frogs, turtles, plus ANY non-hostile mob (modded too) whose death drops nothing (baby animals count): +6 (`rageKillHarmless`)
* hitting him: +6 (`rageHitHim`)
* breaking one of his bear traps: +8 (`rageBreakTrap`)
* killing his lure animal: +14 (`rageKillLure`)
* staying within 24 blocks of the same spot for over 5 minutes: +3 per minute (`rageStayingPut`)

What more rage does: he spawns more often (up to 1 + `rageSpawnBonus` times, ignores the respawn cooldown above 70% rage, and picks the angriest player); events become far more likely and chases much more likely (above 55% rage even a non-chaser will chase, with a shorter grace period); he switches behaviour much more often (erratic); and he gets bolder: stalks and hides closer, no longer freezes when you look at him, pounces from further away, uses voice tricks and sound tricks more.

**Stealth**
* His eyes go dark while he is hiding, peeking, ducking or sleeping.
* New `ducking` animation (a squat, built from your `sit`). Behind a solid 2-3 block tall structure or terrain step he ducks (his head would show above it standing), and the eyes go dark.
* He hides INSIDE ground-level bushes (leaf clusters): he walks in, ducks, eyes dark. Bushes are preferred hiding spots.
* He now walks and runs straight through leaves (pathing allows it and movement glides through leaf blocks while following the ground).

**Spawning and despawning**
* Every spawn, of every kind, only happens where no player could see him (inside any player's field of view with a clear line, up to 256 blocks). The fog watcher now appears just outside your view at the edge of your render distance, so you find him when you turn.
* Every encounter lasts 70-120 s (`lifetimeMinSeconds` / `lifetimeMaxSeconds`) whatever he is doing (chase, grab, sleeping...). When time is up he vanishes at once if nobody can see him, otherwise he sprints for cover and vanishes the moment he is out of sight. Nothing extends his life any more (sleepers etc. now also use 70-120 s).

**Chase animation:** during a REAL chase the run animation switches irregularly between the real-chase run and the fake-chase run (about every 1.4 s), so you can't tell by his animation whether it is real. Fake chases only ever use the fake-chase run.

## Update 16
* **Animations:** `manbear.animation.json` is now your newest file (it includes your own `ducking`). Its `sleep` is byte-identical to the one already in the mod, so the sleep problem is probably not the animation data. The lying body is ~5.4 blocks long, so sleepers now only spawn where the whole strip under him (7x3 blocks) is flat, clear ground, so he can't clip into slopes, trees or walls.
* **Eyes:** no glow while hiding, peeking, sleeping, or ducking (the ducking STANCE as well as the ducking flag).
* **Pet kidnapping** (`petKidnapChanceDenominator`; only possible when you have a tamed pet within 40 blocks, anything owned by you, modded tamables included): he appears behind you, stays out of sight at your back, and once your back has been turned for ~2 s he dashes in, snatches the pet (it rides on him), and carries it 20-50 blocks away, preferably out of your sight. There he sets it down in a CLOSED bear trap: the pet cannot move or be hurt. Find it and RIGHT-CLICK it to free it (you get a message; it also raises his rage). If he spots you looking first, he leaves. If he despawns while carrying it, it is set down in a trap where he was. Test: `/manbear spawn petnapper` (needs a pet nearby).
* **Lightning event** (`lightningEvent`, `lightningChancePerMinute` = 0.12): at night, outside, a bolt strikes and invisible light blocks light up the area around you for about a second; in that light he stands 10-15 blocks in front of you, in view. When the light goes out he is gone. The light blocks are always removed (also on server stop). This event deliberately spawns in view. Test: `/manbear spawn lightning`.
* **Stunning him in a chase:**
  * an iron, diamond or netherite axe hit (any axe of tier iron or better, modded too) stuns him for 2 s (`axeStunTicks`), even breaking a grab;
  * animals near his path distract him: he runs to the nearest animal within ~7 blocks, kills it (not tamed pets, not lure animals) and resumes (`animalDistractTicks` = 40, then a few seconds before another distraction).
* **More rage sources:** stunning/distracting him (+5), rescuing a kidnapped pet (+10), placing campfires (+3), breaking grass BLOCKS (+0.3 each, not tall grass), escaping his grab (+8), and saying "man bear" or "bear" (+5).
* **Calming him down:** planting saplings (-1.5 each, modded saplings too), breeding animals (-4), planting crops (-1), tilling farmland with a hoe (-0.5). All in the config.
* **Saying his name:** with speech recognition on, if he is out there and you say "man bear" or "bear" he has a 5% chance to start a chase, 5% a fake chase, 5% to give up and despawn (`sayName...Chance`); at most once per 10 s. Saying it always raises his rage, even if he is not spawned.
  * It uses the speech recognition built into **Windows** (System.Speech through PowerShell): nothing is downloaded and the audio stays on your PC; only "the player said it" is sent to the server. On macOS/Linux it simply does nothing. It uses the general Windows dictation recognizer, so accuracy depends on your Windows speech setup and mic; it may miss you or occasionally mishear. Turn it off with `speechRecognition=false` in `manbear-client.toml`.
* Delete `config/manbear-common.toml` (and `manbear-client.toml`) for the new options.

## Update 17
* **New model / textures / animations installed** (`manbear_geo_1.json`, `manbear.png`, `manbear_eyes.png`, `manbear_animation_d67156.json`): the model adds two eyebrow bones and keeps the same size, so he is still 5 blocks tall. The new eyes texture also lights up some extra pixels (the eyebrows); they follow the same rule as the eyes (dark while hiding, peeking, ducking, sleeping). The animation file has every animation the mod uses.
* **Intimidation event** (`intimidationChanceDenominator` = 13, a 1-in-13 roll of all spawns): he appears out of sight and walks straight at you at a player's WALKING speed (sprinting outruns him), and does not care whether you look at him. No footsteps at all, only soft breathing that gets a little louder as he nears. He breaks blocks or walks through leaves to reach you. If he gets within ~3 blocks he grabs you and you cannot escape: the exp bar stays empty, space-bashing and axes do nothing, and after ~3.5 s you die (totems can't save you; `intimidationLethal=false` leaves you on 1 heart instead). Escape by outrunning him until his 70-120 s are up. Test: `/manbear spawn intimidation`.
* **Head bug:** his head is now aimed directly at you by the mod itself (vanilla's look control aims ~20 degrees off and pulls the head back towards the body, which is what left it turned away and tilted). When he has nobody to look at, the head straightens out and levels off. No head tilt while ducking, sitting or swimming.
* **Leaves are air:** any part of his body touching leaves (the whole 5-block height now, not just the legs, so low canopies no longer trap him) switches him to a leaf-walking mode that ignores leaves, follows the ground below, and finds ground up to 8 blocks down.
* **Pathfinding:** much less getting stuck.
  * He plans routes for a slim 1-wide, 3-high body instead of his real size (the old size made most gaps between trees, doors and rooms count as "too small", so he found no path). His hitbox is now 0.95 wide (was 1.3).
  * His real body adapts along the way: he picks the biggest stance (stand, crouch, duck, crawl) that fits where he is AND one step ahead, re-checked every 3 ticks.
  * He opens wooden doors on his way.
  * When he is wedged he cycles through jumping, sidestepping left, sidestepping right, recomputing the path, and backing off to run at it again, before giving up.
* Delete `config/manbear-common.toml` for the new options.

## Update 18: pit lure, kidnapping, your bird, chase voice, projectiles

Everything below has a config entry (`manbear-common.toml`) and can be tested with `/manbear spawn pitlure`, `/manbear spawn kidnapper` and `/manbear bird`.

**Pit lure** (called `PITLURE` in the code because `LURE` was already the animal lure)
- He stands silent behind you, like the behind-you event, waiting for you to notice him (gives up after `pitLureNoticeSeconds`).
- The moment you see him he digs a 3x3 pit (`pitLureDepth`, default 5 = you cannot jump out, even with Jump Boost) in the direction he is about to run, and runs past it to the far side. The nine surface blocks are left untouched, so the cover is real grass that matches the ground around it; only the volume under them is dug out.
- He waits beyond the pit and keeps it exactly on the line between you and him, so walking straight to him means walking over it. Step on the cover: a crunch, four ticks later all nine blocks break and you fall. Falling in starts a real chase (he roars, and from the edge he reaches down into the pit instead of trying to walk in; his grab pulls you out).
- If you walk around the pit, never follow (`pitLureFollowSeconds`), or wander off, he runs off and despawns, and the pit is filled back in (`pitLureFillUnused`). Mining one of the cover blocks disarms the pit. At most `maxPitTraps` armed pits exist at once.
- Armed pits are saved with the world. Only players trigger them (not creative/spectator).

**Kidnapping**
- He spawns 20-28 blocks behind you and sneaks up in his hunting crouch, only slightly faster than you are walking. Look at him for about half a second and he backs away (still facing you) and becomes a normal stalker. He may try again later (twice at most).
- When he reaches you he grabs you and runs to the nearest cave he can actually walk to (floor at least 6 blocks under the surface, no skylight, validated with his pathfinder; searches up to `kidnapCaveSearchRadius`). If there is none he just carries you into the distance.
- The exp bar is the escape bar. You need 50 presses at 0 rage up to 100 at full rage (`kidnapPressesMin/Max`), exactly that many, no drain. The time you get is never shorter than the time needed to mash that many presses at ~6 per second plus 2 s, however close the cave is (a close cave only makes him walk slower, or stand and hold you).
- Fail: the screen fades to black (new `BlackoutPacket` + overlay), you are teleported to a random safe surface spot between ~60 and `kidnapTeleportRadius` (200) blocks away and left at half a heart; he vanishes. Break free: he is dazed for a moment and chases you (`kidnapEscapeChases`; otherwise he flees). An iron axe hit breaks the grip just as it does for his normal grab.

**Your bird**
- Every player gets one bird (a vanilla parrot model, flying like one) that hovers around you within `birdRadius` (30) blocks, teleports back if you outrun it, and chirps every `birdChirpMinSeconds`-`birdChirpMaxSeconds`. Five synthesised songbird chirps are included (`sounds/chirp1-5.ogg`, replace them with your own if you like).
- While the Manbear exists anywhere (any event, any dimension) it stops chirping and flies away; once he is gone it flies back in from about 50 blocks away after `birdReturnDelaySeconds`. The silence is the warning.
- It is invulnerable, cannot be fed, leashed or tamed, is ignored by his animal-kill distractions, and is saved per player (a stranded duplicate removes itself). `/manbear bird` re-creates yours.

**Chase voice**: in fake and real chases he continuously roars and screams (a new overlapping sound every 0.7-1.4 s, new `chase_scream` sound event), from about a quarter of `chaseVoiceMaxVolume` at 64 blocks to full volume on top of you.

**Projectiles**: arrows, snowballs, eggs, tridents etc. that hit him while he stalks, hides or peeks (also while he waits in a pit lure or sneaks up for a kidnapping) make him flee and despawn, and add `rageProjectile` rage. If your rage is already above `projectileFleeRageLimit` (40) he does not flee; it only adds rage. In any other mode it is an ordinary hit.

Other changes: pit-lure and kidnapping encounters get +40 s of lifespan (they need time to play out), and a new `carry_run` animation (legs from `run2`, upper body from `grab`) is used while he runs with you.

## Update 19: evidence, fake death, your new animations

**Animations:** `manbear.animation.json` is your newest file. `grab` is the 4.4 s leaning pose (normal grab, intimidation grab, and the standing pose of the kidnapping), `ducking` has the animated eyebrows, `carry_run` is yours. The new `Intimidation` clip is his walk during the intimidation event (he approaches in it instead of the plain walk), `Leaf_walk` plays while he walks with leaves touching his body, and `leaf_run` while he chases through leaves. Clip names are matched ignoring upper/lower case and each is only played if it exists in the file, so renaming or removing one never causes a T-pose (he falls back to the normal walk/run). There is no collapse/death clip in the file, so the fake death still uses the sleeping pose.

**Evidence** (`leaveEvidence`): if he stalks, hides or peeks and then despawns without you ever having noticed him (you never looked straight at him for about a third of a second, never hit him, and he never chased, grabbed or did any event), he leaves something on the block where he vanished. Rolled with weights (`evidenceFootprints` 40, `evidenceBearTrap` 10, `evidenceDeadAnimals` 15, `evidenceLeafPile` 30, `evidencePit` 5):
* **Footprints:** a trail of the existing giant footprint block (up to 14 prints, one stride apart, left/right alternating) following the way he really walked, or coming in from the direction he watched you from if he mostly stood still. They are `fading`: they wear away in a few minutes and the rain washes them off. (The raid footprints inside houses are unchanged and permanent.)
* **Bear trap:** an armed trap, placed exactly like the ones he lays (same cap).
* **Dead animals:** 2-4 dead cows, pigs, sheep, chickens or rabbits lying around the spot. This is a small new entity (`manbear:corpse`) that remembers which animal it is; the client draws that animal's real model on its side with the red death tint. They rot away after `corpseMinutes` (12).
* **Pile of leaves:** a small heap of the same leaves as the trees nearby (persistent, they never decay).
* **Pit fall:** a hidden pit exactly like the pit-lure pit (grass roof, `pitLureDepth` deep) on or next to the spot; it counts toward `maxPitTraps`.
If the ground cannot take the rolled type (a pit needs flat grass, for example) footprints are used instead. Test with `/manbear evidence <footprints|trap|animals|leaves|pit>` (needs a Manbear to exist; places it 5 blocks in front of you). `/manbear remove` leaves nothing.

**Fake death** (`fakeDeath`): every chase he picks a random number of hits between `fakeDeathMinHits` and `fakeDeathMaxHits` (5-10). Only hits made **with a weapon** count: a sword, axe or trident in your hand (or any item with an attack damage bonus of 3 or more, so modded weapons work), or an arrow / thrown trident. Fists, pickaxes, shovels, snowballs and eggs do not count (an axe stun does). The weapon hit that reaches the number makes him collapse with his death cry and lie still in the sleeping pose, eyes dark, taking no damage. He is still breathing, very faintly. The moment you let your guard down he is up with a roar and a lunge ready, chasing again: when you have looked away from him for about 1.25 s, when you are more than 20 blocks away, when you walk right up to him (under 3 blocks), when you hit him, or after `fakeDeathMaxSeconds` (40) anyway. Each new chase (and each resurrection) rolls a new number. Test with `/manbear mode playdead`.

Delete `config/manbear-common.toml` to get the new options.

## Update 20: client fog, sounds from his body, rage scratches, hallucination

**Client fog** (`manbear-client.toml`, section `[fog]`; everything also live with `/manbearfog`): atmospheric fog on your own computer only. It is built from YOUR render distance, so it looks the same at 6 chunks or 32: by default it closes in at 85% of your render distance and begins at 20% of that. It gets thicker at sunrise (morning mist), a little at dusk and at night, in rain and thunder, in swamps / dark forests / taigas / rivers / forests, and thinner in deserts and badlands and as you climb above sea level; underground (no skylight) it fades to vanilla. It is round (spherical) like real fog. Settings: `thickness` (more/less thick), `distance` (closer/further), `startFraction` (how hazy the near field is), `color` (hex), `colorBlend`, `brightness`, the four realism toggles, `otherDimensions`, and `shaderMultiplier`. `/manbearfog status | on | off | thickness | distance | start | brightness | blend | color <hex> | reset`.
*Shader packs:* the fog is applied through Forge's fog events (no mixins, nothing replaced), which is the same fog state Iris / Oculus / OptiFine read, and the mod detects a running shader pack and applies `shaderMultiplier` so you can compensate. I have not been able to test it against any particular pack: packs that draw their own fog and ignore the game's may look different, and that is what `shaderMultiplier` is for.
The **fog watcher** now stands at `fogWatcherDistanceFactor` (0.70-0.77) of the render distance instead of 90-97%, inside the thick fog where he is still a dark shape. Raise it if you thin the fog, lower it if you thicken it.

**Sounds** (`allSoundsFromHim`): every sound he makes now comes from where he stands. Removed: the extra roars played at your own position when he pounces or is stunned, the animal mimics from behind you, and his door / chest / barrel / torch tricks now come from him (louder, since he is usually far off). Still exempt: his voice mimic (already from him), a lured animal's own cry, and the **ambient sounds** (the phantom roars and twigs from `AmbientHorror`), which now stay completely silent, and their queue is dropped, for as long as he is spawned anywhere.

**Rage scratches** (`rageScratches`): every 5-15 minutes (per player, `rageScratchMinMinutes` / `rageScratchMaxMinutes`) a number from 0 to 100 appears clawed into a tree trunk 9-26 blocks from you, mostly in front of you, on the face turned toward you, at a height his claws reach. It is your own current rage (the same value `/manbear rage` shows). New block `manbear:rage_scratch` with a block entity that remembers the number; the client draws it on the log like a sign draws its text (pale, bold, with a gouge shadow), so it needs no image files at all. The marks weather away after roughly 15 minutes. The scratching makes a faint sound only when he is not spawned. `/manbear scratch` places one now.

**Hallucination** (kind `hallucination`, `hallucinationChanceDenominator`): he waits motionless in real woods 38-58 blocks away. 15-30 blocks from you, between you and the woods, stands a copy of YOU: your skin, your arm model, your armour and held item, your name above its head, visible only to you (new entity `manbear:doppelganger`). It stares until you really look at it, then runs for the woods and waits there. Follow it and get close to him: lunge once and vanish 30%, fake chase 40%, flee 20%, real chase 10% (`hallucinationLunge` etc.). Hit or right-click your double and it is gone. If you never look, never follow, or wander off, he leaves. `/manbear spawn hallucination`.


## Update 21: textured world fog

The client fog now also uses actual translucent, animated world particles with a custom cloudy sprite. Wisps drift around the player and fade with the same surface/cave exposure as the atmospheric fog; particle tint follows the configured fog `color`. The original distance fog is retained to blend the landscape into the mist at a distance, while the particles create visible texture nearby.

In `config/manbear-client.toml` under `[fog]`, `particlesEnabled` toggles the textured wisps, `particleDensity` changes how often they spawn (0.25-3.0), `particleOpacity` changes their per-particle opacity (0.02-0.5; default 0.28), `particleMinDistance` keeps the visible edge of each wisp at least this many blocks from the camera (0-32; default 10), and `particleFadeDistance` controls how far before that boundary the particles smoothly fade out (1-24; default 8). `particleMaxDistance` sets the maximum horizontal spawn distance from the player (16-128; default 48). Wisps spawn throughout the area between the protected inner radius and this outer radius; area-weighted radius sampling helps avoid a narrow-looking ring. If the configured outer radius is too close to the current minimum radius, the effective radius is raised enough to preserve the safety boundary. Wisps gradually fade as you approach, then are removed at the minimum-distance boundary. In game: `/manbearfog particles on|off`, `/manbearfog particle-density <value>`, `/manbearfog particle-opacity <value>`, `/manbearfog particle-min-distance <blocks>`, `/manbearfog particle-fade-distance <blocks>`, `/manbearfog particle-max-distance <blocks>`, and `/manbearfog status`. Particles are client-side and do not need server-side entity spawning.


## Update 23: proximity fade for textured fog

Textured fog particles now smoothly fade out as the player approaches the configured minimum camera distance instead of suddenly disappearing. The fade uses a smoothstep curve and stops particles before they can enter the protected near-camera radius. `particleFadeDistance` defaults to 8 blocks and can be changed in the client config or with `/manbearfog particle-fade-distance <blocks>` (1-24). The outer spawn radius defaults to 48 blocks and is adjustable with `/manbearfog particle-max-distance <blocks>` (16-128).

## Update 25: leaf traversal, unpredictable chases, and the Manbear den

* **Leaves act like air for navigation and movement.** The route evaluator classifies leaf blocks as open space, and the Manbear's leaf travel avoids leaf collision without freezing when the ground is farther below. Solid non-leaf blocks still obstruct him normally.
* **Silent stare-and-rush ability** (defaults: 32% chance to arm per fake/real chase): he can stop dead for a random 4-10 seconds and make no sounds. If the player closes the gap during the stare, he rushes at 5x his player-relative sprint speed for 3 seconds; otherwise he resumes the chase normally. Tunable under `[chase_unpredictability]` with `chaseStareAbilityChance`, `chaseStareMinSeconds`, `chaseStareMaxSeconds`, `chaseBurstSeconds`, and `chaseBurstSpeedMultiplier`.
* **Teleport-ahead ambush** (32% chance to arm per fake/real chase): while the player is moving quickly, he may teleport once into a valid spot ahead, often toward the learned base if the player appears to be running home. Tunable with `chaseTeleportAbilityChance`.
* **Fake chases land hits.** He tries to land two real hits by default, then pauses briefly and leaves. `fakeChaseHitsMin` / `fakeChaseHitsMax` set the number of hits.
* **One persistent den per world.** After the mod has learned a base and the player accumulates about 10 minutes near it by default (configurable), a cave den is carved 50-200 blocks from that base (default; configurable). It has an entrance tunnel, branching passages, a large chamber, bones, cobwebs, dim lanterns and permanent animal remains. The den's position and state are saved with the world.
* **Following him home:** sometimes, after he notices the player during a stalking encounter, he attempts to return to his den. Following too closely, sprinting after him, or staring at him nearby triggers a real chase. If he reaches the entrance, he disappears inside.
* **Disturbing the den starts a 30-minute natural-spawn lockout.** Breaking or placing blocks in the den, fire/lava inside it, or filling it with enough living mobs destroys the den. When the lockout expires, the tracked player's rage jumps to 100 and a replacement den can form. Lockout duration and mob threshold are configurable under `[manbear_den]` (`denEnabled`, `denHomeTimeMinutes`, `denMinBaseDistance`, `denMaxBaseDistance`, `denDestroyedCooldownMinutes`, `denMobDestroyThreshold`, `denReturnChance`).

The new den and chase values live in `config/manbear-common.toml`. A full Forge 1.20.1 build and in-game multiplayer test are still needed to validate navigation/pathing and world generation end-to-end.


## Update 28: buried den placement fix

* **Ground-level detection:** the den generator now scans down through leaf canopies and tree trunks to find the actual terrain surface. It no longer mistakes the top of a forest canopy for ground.
* **Underground validation:** candidate sites must have a solid roof over the main chamber, a dry carving volume, and an entrance location with a compatible ground height.
* **Existing-world repair:** if a den saved by an older version is detected far above the true ground, its stranded den decorations and permanent animal remains are cleaned up and its location is regenerated without imposing the destruction cooldown. Cleanup waits until the affected chunks are loaded.

The fix is part of the source project; a Forge 1.20.1 build and in-game verification are still required.

## Update 29: larger hitbox, den kidnapping, hallucination variants, torch flicker

* **Hitbox matches the model:** standing dimensions are now 3 × 5 blocks. When a route runs under low ceilings or through a narrow gap, the stance selector prefers the crawl stance and uses the existing crawl animation. It grows back to a larger stance when there is room.
* **Kidnapping to the den:** if a generated den exists in the same dimension, a failed kidnapping takes the player to the den's main chamber instead of a random far-away location, leaving them at half a heart. The Manbear guards the entrance for a random 1–3 minutes. Trying to leave or approach him starts a fake or real chase. He then returns to the chamber and sleeps; careful crouch-walking produces much less vibration than normal walking, while noisy actions can wake him into an immediate chase. If no den is available, the original random-location kidnapping remains.
* **Random hallucination fix:** a Manbear with the hallucination trait now enters the event on its normal AI startup instead of requiring a manual mode command. Each hallucination chooses between the classic copy that leads you toward the Manbear and a tree-side copy that bolts behind the trunk when noticed or approached (5–10 block trigger radius) and vanishes once hidden. If a suitable tree cannot be found, it falls back to the classic version.
* **Stalking torch cue:** while a Manbear is stalking, hiding, or peeking within 40 blocks, a random cue can make nearby placed torches or an offhand torch emit extra flame/smoke particles and a brief fizzle. The effect is client-side and leaves block states alone; vanilla torch block-light levels do not change. Configure with `torchFlickerEnabled` and `torchFlickerChance` under `[torch_flicker]` in `config/manbear-common.toml`.

The Forge 1.20.1 source still needs a build and in-game test to validate entity navigation, collision dimensions, the full den escape loop, and client-side torch visuals.


## Update 31: den entrance, natural tunnels, and persistent kidnapping

* **Surface discovery:** new dens now mark the entrance with a rough mossy-stone arch, bone piles, skulls, cobwebs, and several permanent animal remains. Existing dens are upgraded once when the full den region is loaded.
* **More natural cave layout:** widened, uneven passages and intersecting side tunnels create a small loop and feeding alcove. The chamber adds stone variation, redstone ore and torches, skulls, chains, soul lanterns, cobwebs, and pointed dripstone. Existing player-placed blocks are preserved during the one-time den upgrade.
* **Physical den kidnapping:** if a den is present, the Manbear carries the player along the den entrance and descending ramp, then drops them in the main chamber at half a heart. This path no longer uses the blackout screen or a long-distance teleport. The original fallback remains for worlds without a usable den.
* **Persistent den guardian:** the same Manbear guards the exit, walks back to the chamber, and sleeps after the 1–3 minute wait. The guardian state and captive UUID are saved, the Manbear's lifespan is suspended during den custody, and the world data reserves the active Manbear slot for that guardian across chunk unloads/reloads. The captive is kept assigned to the den if the Manbear or player is temporarily unloaded.


## Update 32: adaptive intelligence, den escort, and corpse interactions
* **Persistent adaptation:** every successful chase escape advances the Manbear's strategy stage for that player. Repeated sprint escapes increase chase speed and route-ambush/teleport chances; repeated escapes toward a base or enclosed shelter teach him to break wider openings. Later encounters last longer and more often use silent-stare tactics.
* **Locally remixed chat taunts:** with `chatEchoEnabled` on, the server saves up to 12 short, sanitized fragments from the player's in-game chat in the world profile and locally remixes them into new private taunts while the Manbear is stalking nearby. No text is sent outside the Minecraft world/server. Disable the setting to stop collecting and using these fragments.
* **Den escort reliability:** a den kidnapping reserves the Manbear as the den guardian before the long walk, extends his lifetime while escorting, uses shorter reachable waypoints with route retries, and will not enter the den's interior route before he has reached the entrance. Escaping/disconnecting releases the reservation appropriately. The den kidnapping path no longer announces its destination in chat.
* **Varied cave generation:** newly generated dens use a per-den random layout with irregular chamber edges and multiple crooked side passages. Surface clues are sparse and uneven, with small bone fragments/corpses placed at their local ground height instead of at one shared Y level.
* **Animal remains:** corpse markers disappear when their support block is removed or no longer provides a solid top face. Hitting one removes it and drops rotten flesh.

Chat echo settings: `chatEchoEnabled`, `chatEchoChance`, and `chatEchoIntervalTicks` in `config/manbear-common.toml`.

## Update 34: chat-fragment taunts restored

The optional local voice-cloning helper has been removed. This separate chat-taunt feature learns a small set of scrubbed, short fragments from in-game chat and privately combines different fragments into a newly phrased text taunt. The fragments are saved with the player's world profile; chat is not sent to an external service. Disable `chatEchoEnabled` in `manbear-common.toml` to stop collecting and remixing chat fragments. The microphone-based audio collage is separate and described in Update 35.


## Update 35: microphone voice collage

The Manbear's voice mimic now uses actual microphone audio snippets rather than in-game chat text. The client detects pauses and low-energy parts in speech, stores short fragments in RAM, and randomly shuffles 2–4 pieces into a new, eerie audio collage. It uses only the player's recorded voice; no transcript, external model, or voice-cloning service is involved. Since speech doesn't have silence between every word, the fragments are approximate and may contain a full word, part of a word, or a few words. Use `/manbearvoice sampling off` to stop listening and clear the local buffer. Multiplayer sharing is a separate opt-in (`/manbearvoice share on`) and should only be enabled when you consent to sending mic audio to that server.


## Update 36: cross-utterance microphone collages

Microphone snippets are now grouped by the separate thing the player said, rather than stored as an unlabelled pool. Each Manbear voice collage deliberately pulls from at least two different sayings, then adds extra snippets from other sayings when available. The status command reports both fragment count and distinct sayings represented in memory. If only one saying has been captured, voice collage playback waits until another saying is available. A continuous saying longer than the capture chunk limit keeps the same saying ID, so it does not falsely count as multiple things the player said.


## Update 37: independent Manbear mind and persistent intentions

* **Long-running plans:** the Manbear can interrupt ordinary stalking to investigate recent player-caused block changes, scout a likely travel hotspot, or visit a private ritual location. These are separate from chase modes, so they are goals pursued over time rather than a one-tick random event.
* **Curiosity about construction:** block placements and break events are recorded per player, dimension, position, and world time. When the player has left the area, the Manbear may investigate the change from a nearby standable spot instead of trying to occupy the changed block.
* **Hypothesis and testing:** frequent player hotspots become uncertain route hypotheses. The Manbear can camp at a candidate location and update confidence depending on whether the player is observed nearby; confidence is clamped and stored with the player's world profile.
* **Temperament:** each Manbear gets six persistent behavior tendencies—curiosity, patience, territoriality, distractibility, ritualism, and caution. These bias goal selection, approach speed, den-associated routines, and likelihood of inspecting nearby animals, so separate Manbears don't all reason identically.
* **Distractions and unfinished business:** while pursuing a plan, the Manbear may inspect a nearby animal, then resume the earlier plan. Chases, close approaches, and other special modes can interrupt an intention; the interrupted goal is saved and can be resumed later.
* **Fallible movement:** if a goal is blocked, he pauses, retries from up to three nearby approach positions, and can abandon the attempt. He does not teleport to the goal or gain perfect knowledge of the player's location.
* **Independent routines:** occasionally, the Manbear travels to a personal anchor and sits in silence for a while. A territorial temperament can make him prefer a location near his den. Routine anchors, personality, current goals, and resumable goals are stored in the entity's save data.

These behaviors share one goal layer over the existing stalking/chase AI. They intentionally have cooldowns and can fail, so curiosity and routines should be uncommon enough to feel like independent behavior rather than constant interruption. A full Forge 1.20.1 build and in-game observation are still required to validate timing, navigation, and how often the new plans occur in different terrain.

## Update 38: evidence-based perception, deception, and misconceptions

* **Evidence-based perception:** during stalking and long-running mind plans, he uses direct sight, recently observed block changes, and noisy short-range movement clues to estimate the player's last-known position. Confidence decays over time. When clues expire, he may stop searching precisely, wait, or reposition rather than tracking through walls. Active chase behavior remains governed by the existing chase AI.
* **Player decoys:** nearby dropped items can become ambiguous clues. He may investigate an item that is far enough from the player's last confidently known position, then interpret whether it disappeared as evidence. The interpretation can be wrong, which feeds into his route beliefs.
* **Multi-step deception:** when he has a clear view of the player, he can occasionally stage a chest-like sound at a separate location while moving toward an interception point. He only judges the player's reaction while he can actually see it; a successful lure can lead into an existing ambush or fake chase.
* **Failed-approach memory:** blocked investigation points are remembered and avoided in later plans, with nearby alternate approach points tried first. The recent failed-location list persists with the Manbear entity.
* **Environmental traces:** some investigations and deceptions leave a footprint trail along his actual recent movement, providing a physical clue that he passed through the area.
* **Misconceptions:** route beliefs are updated gradually from tests, bait, and deceptive cues instead of becoming certain immediately. Misleading evidence can make him favor the wrong location; subsequent observations can correct that belief.
* **Player-specific state isolation:** when the Manbear changes targets, his last-known position and recent world clue markers are reset so one player's observations don't leak into another player's encounter.

These behaviors are intentionally occasional and imperfect. They use the existing world-profile evidence and entity-save system rather than requiring external services or extra assets. A full Forge 1.20.1 build and in-game testing are still needed to verify navigation, timing, and edge cases across saves and multiplayer worlds.


## Update 39: living predator, den trophies, and pet abduction

* **Combat-style adaptation:** records confirmed ranged hits, melee hits, and shield use. During suitable chases he sometimes flanks ranged fighters, circles raised shields, or changes angle and briefly slows against a player who repeatedly fights in melee. He does not instantly counter a weapon style after one observation.
* **Home rummaging and stolen trophies:** when a raid is queued, the Manbear can rummage through a few chest/barrel slots while the base area is loaded and the player is away. Containers remain intact by default. If the base chunks were unloaded for the entire trip, the existing return-trip fallback still lets the player discover missing items. Stolen items are tracked in world-saved data and inserted into a concealed den trophy chest when the den area is loaded.
* **Pet abduction:** when a petnapper catches a tamed pet, there is a configurable chance (`petToDenChance`, default 0.38) of taking it to a currently loaded den in the same dimension. Otherwise, the established nearby captive/trap behavior is used. Pets in the den remain alive and can be rescued by interacting with them. `denPetAbductionEnabled` and `petToDenChance` control this behavior.
* **Independent drives:** fatigue, hunger, and territorial stress influence pauses, curiosity, and return-to-den choices (`mindNeedsEnabled`). His curiosity/patience/territoriality/distractibility/ritualism/caution remain individually randomized and saved.
* **Environmental and trap intelligence:** small ambient signs and wildlife reactions can hint that he is nearby; he can inspect player-visible trap components and remember routes around previously observed hazards (`environmentReactionsEnabled`, `trapLearningEnabled`).
* **Memory and property:** recent failed approaches, place preferences, and selected item types persist with his entity. He can investigate familiar dropped-item types and make small, non-destructive den/territory routines rather than behaving as a purely player-focused chase machine.
* **Fairness safeguards:** the den is not force-loaded for a pet abduction; if the entrance/main room isn't already loaded, the pet stays with the existing captive flow. A chest raid never consumes the whole chest, and the world trophy ledger is bounded to avoid unbounded save growth.

As usual, run a full Forge 1.20.1 build and test a copied world before using this update in a primary save; source/archive checks are not a substitute for an in-game test.


## Update 40: deeper reasoning and multi-encounter planning

The Manbear's independent-mind layer now combines several reasoning signals instead of treating them as unrelated event rolls:

* **Goal inference:** his player profile estimates broad goals (building, gathering, exploring, returning home, or cave exploration) from recent movement and visible world changes. These are hypotheses, not exact knowledge of player intent.
* **Active information gathering:** he can take an observation position near a likely objective and wait for new evidence, then reinforce or weaken his current hypothesis. Existing route tests and trap inspections remain part of the same decision flow.
* **Episodic memory:** finished investigations and chase escapes are recorded with the outcome and a compact explanation of why a plan may have failed. Repeated ignored lures/investigations reduce the chance that he repeats the same tactic.
* **Counterfactual updates:** a failed plan can be tagged as an unconfirmed route, ignored lure, unhelpful bait, or likely path/clearance problem. Failed approach locations are avoided for a while, and alternate positions are scored using live path previews when available.
* **Opportunity recognition:** while stalking with line of sight, he may notice when the player is committed to using an item, swimming, riding, low on health, or sprinting away, then attempt a reachable interception. He only exploits the opportunity if it still exists when he arrives.
* **Outdated beliefs:** route confidence drifts back toward uncertainty when not confirmed, while player-goal confidence fades when new evidence stops supporting it.
* **Multi-encounter campaigns:** completed investigation objectives persist in the per-player world profile for up to seven in-game days and may be resumed by a later Manbear encounter. Campaign stages advance after useful evidence or a failed attempt, and stop after several attempts instead of looping forever.
* **Physical limitations:** candidate positions are scored against the entity's path navigation and vertical travel cost. Unreachable routes are treated as expensive experiments, not as impossible knowledge-free teleport targets; if a route fails in practice, the failure is remembered.

The profile's new episodic memories, inferred goal, route-belief timestamps, and campaign progress are saved through `ManbearData`, so they can survive world reloads. Old saves remain loadable; old route beliefs without timestamps are treated as stale and mostly uncertain.

## Update 41 — Scripted encounter director

This update adds a shared cooldown/state layer for longer-form encounters. The new optional `scriptedEncountersEnabled` common setting controls the added scripted encounter group.

- **House rehearsal:** On separate visits, the Manbear may inspect different doors or likely entrances around the learned base, playing a brief door test cue and remembering which entrance he examined last.
- **Interrupted task memory:** If the player interrupts a sufficiently old investigation, its kind, focus, dimension, and timestamp are stored. The Manbear can resume the unfinished intention during a later encounter instead of starting fresh every time.
- **Contradictory traces:** He can stage two divergent footprint paths near a familiar area, then adjust his location belief from what happens afterward.
- **Watch-testing:** When a player is actively watching him from a distance, he can freeze, then attempt a small relocation during a gap in observation.
- **Borrowed room:** Once stolen items reach the den trophy chest, the den can construct a crooked miniature room with copies of up to two recovered item types displayed on its wall. Originals remain in the trophy chest, so destroying a display does not destroy the recovered loot.
- **Deliberate escape experiment:** During some pursuits, the Manbear briefly lets the player open a gap while watching the route taken. The event records what happened and influences the route belief instead of always forcing an immediate attack.
- **Contextual microphone echo:** Some voice-collage events can be spatially anchored near a remembered place where the player previously made a world change. Audio is still assembled locally from the player's opted-in microphone buffer; the mod does not transcribe the recording.

These events are conditional and cooldown-limited. They do not replace the existing chase, stalking, den, hallucination, voice, fog, or pet behaviors. Use a backup world when testing a new source build, especially where den generation or entity AI is involved.


## Update 42 — living-predator instincts and independent projects

This update extends the existing intelligence layer with bounded, situational behaviors. It deliberately does **not** add the previously discussed weather/time-rhythm awareness system.

* **Opportunity-cost decisions:** During a long pursuit, fatigue, temporary weapon injuries, and den priorities can outweigh the immediate chase. He may abandon it and retreat toward his den or disengage, recording why he stopped instead of always pursuing until a timer expires.
* **Strategic concealment:** If the target has been watching him, his learned monitoring tendency affects how likely he is to stop, wait for a gap in attention, and walk to real cover. This uses navigation rather than teleporting, and can be interrupted if the player gets too close.
* **A revisable theory of the player:** Per-player estimates track whether the player investigates cues, watches the Manbear, or follows him toward the den. Those estimates affect lure and den-retreat choices and continue to change with observed outcomes; they are not certainties.
* **Adaptive den remodeling:** After a player enters the den and then leaves, the loaded den can gradually gain one of five curved natural-rock side passages, no more than five remodels per den and with a five-minute cooldown between changes. Only natural terrain is carved, player-placed blocks are preserved, and the extra tunnels are included in the den's interaction/detection boundary.
* **Wildlife as a distraction:** While stalking, the Manbear may approach an untamed nearby animal, play a species-appropriate sound from its location, and nudge its normal navigation away from himself. He uses this as a test of whether the player investigates, without damaging or taking control of tamed pets.
* **Learned simple object use:** He can remember doors, trapdoors, fence gates, and levers after passing close enough to inspect them, then occasionally manipulate a known mechanism when the player is distant and not watching. Iron doors/trapdoors are excluded so he doesn't bypass redstone locks. Memory is bounded and dimension-specific.
* **Temporary injuries:** Significant weapon hits can leave him temporarily slower and more fatigued. His injury state is saved with the entity, decays over time, and recovers faster when resting near his den. There is no permanent stat penalty.
* **Private projects:** Infrequently, he may travel to a secluded natural location away from the player's base and den, spend time there, and leave a tiny cairn as evidence of an activity the player didn't directly witness. Sites must be natural and clear; player-built blocks are not overwritten.

All new behavior groups have independent `manbear-common.toml` toggles: `adaptiveDenRemodelingEnabled`, `creatureInstrumentsEnabled`, `learnedObjectUseEnabled`, `woundMemoryEnabled`, `privateProjectsEnabled`, `opportunityCostEnabled`, `strategicConcealmentEnabled`, and `playerTheoryEnabled`. Each feature uses cooldowns and validation checks to prevent constant event spam or forced chunk loads.

A complete Forge 1.20.1 build and in-game testing are still required. Den remodeling and object interaction should first be tested in a backup world, especially if the player has modified the den or placed mechanisms nearby.

## Update 44 — positional voice callouts and loud-vocal activity

* **Position-aware word collages:** On Windows, the existing local `System.Speech` recognizer now extracts audio for a small allowlist of recognized words: `behind`, `you`, `outside`, `your`, `house`, `to`, `left`, `right`, `in`, `front`, and `of`. The actual word-audio clips stay in the local client's RAM. During an eligible voice-mimic event, the server sends only a phrase code and the Manbear's world position; the client assembles phrases such as “Behind you”, “Outside your house”, “To your left/right”, and “In front of you” in the correct word order. If a required word has not been recognized yet, the client falls back to the existing general voice collage. Directional word capture requires Windows speech recognition and microphone sampling; on other operating systems, ordinary voice collages continue to work but word-specific callouts are unavailable.
* **Loud-vocal event detection:** An optional local microphone detector looks for sustained, high-volume bursts above a configurable RMS threshold and minimum duration. This is an audio-level heuristic, not perfect scream recognition; unusually loud speech, laughter, or microphone noise can also register. It is disabled by default. Turn it on with `/manbearvoice screams on` and off with `/manbearvoice screams off`. It requires microphone sampling. The client sends only a rate-limited event notification; it does not upload or separately save the detected audio.
* **Spawn activity response:** The world profile stores a lifetime loud-vocal event count and a capped, decaying recent-activity value. Recent events can gently increase spawn chance and player-selection weight. By default each event adds 0.12 spawn multiplier, capped at 1.0 extra multiplier, and one activity stack decays every 10 in-game minutes. Server controls are `screamActivityEnabled`, `screamSpawnBonusPerSignal`, `screamSpawnBonusMax`, and `screamActivityDecayTicks`.
* **Client controls:** `/manbearvoice directions on|off` controls local word-audio capture, and `/manbearvoice status` reports the local snippet counts and loud-vocal event count. `positionalVoiceWords`, `screamDetection`, `screamRmsThreshold`, and `screamMinDurationMs` live in the client config. The existing `/manbearvoice share` setting remains separate; positional word snippets are never included in multiplayer voice uploads.

Do not deliberately shout to trigger this system. Normal play does not require raising your voice; keep the detector off if it produces false positives or you prefer not to use microphone-based loudness detection. A full Forge 1.20.1 build and in-game testing are still required, especially on Windows with the default speech recognizer and with the microphone's input level adjusted to the player's normal environment.
