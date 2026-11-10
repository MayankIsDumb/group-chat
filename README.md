# My Mod — Fabric mod for Minecraft 1.21.11

A ready-to-code Fabric modding environment.

| Component | Version |
| --- | --- |
| Minecraft | 1.21.11 (Java 21) |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.141.6+1.21.11 |
| Fabric Loom | 1.19.4 (`net.fabricmc.fabric-loom-remap`) |
| Mappings | Mojang official (`loom.officialMojangMappings()`) |
| Gradle | 9.8.2 (wrapper) |

## Commands

```bash
./gradlew build        # compile + package -> build/libs/mymod-1.0.0.jar
./gradlew runClient    # launch the game with the mod (dev account)
./gradlew runServer    # launch a dev server (accept run/eula.txt on first run)
./gradlew genSources   # decompile Minecraft into IDE-navigable sources
./gradlew --refresh-dependencies build   # re-resolve after bumping versions
```

On Windows use `gradlew.bat` (or `./gradlew` from Git Bash).

## Layout

```
src/main/java/com/example/mymod/       server + common code (MyMod entrypoint)
src/main/java/com/example/mymod/mixin/ common mixins  (mymod.mixins.json)
src/main/resources/fabric.mod.json     mod metadata, entrypoints, dependencies
src/client/java/com/example/mymod/client/      client-only code (MyModClient)
src/client/java/com/example/mymod/client/mixin/ client mixins (mymod.client.mixins.json)
src/main/resources/assets/mymod/       textures, models, lang files, icon
```

Source sets are split by Loom (`splitEnvironmentSourceSets()`), so client-only
code lives in `src/client` and is never loaded on a dedicated server.

## Renaming the mod

Change the id in `gradle.properties`/`build.gradle` (`mymod`), `settings.gradle`
(`rootProject.name`), `fabric.mod.json` (`id`, entrypoints, mixin configs,
`assets/<id>/` folder), and the `com.example.mymod` package.

## Version pins

All versions live in `gradle.properties`. Current versions for 1.21.11 are listed
on <https://fabricmc.net/develop>. `org.gradle.java.installations.paths` points at
the JDK 21 install so the game runs on Java 21 even though the local Gradle
daemon defaults to a newer JDK.

## Running outside the dev environment

Install the Fabric loader for 1.21.11, then drop `build/libs/mymod-1.0.0.jar`
(unmapped/remapped jar — not `-sources`) and
[Fabric API 0.141.6+1.21.11](https://modrinth.com/mod/fabric-api) into `.minecraft/mods`.
