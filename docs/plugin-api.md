# Plugin API

`dev.dackoro.mobbattle.MobBattleApi` lets another server plugin start and observe **ownerless NPC battles**. It supports three kinds:
- **duels**;
- **battle royales**;
- **focus battles**, where several NPCs attack one target.

The API never exposes ECS references, and it doesn't let callers manage fighters directly. Battles run with the server's current Mob Battle settings: countdown, arena, timer, time-up rule, presentation. See [battles.md](battles.md) and [settings.md](settings.md).

Admins can start the same events from the console. See [server-setup.md](server-setup.md#server-events).

## Dependency and access

The API ships inside the Mob Battle jar. There is no separate Maven artifact or separately versioned API module.

1. Add the Mob Battle jar to your plugin's Gradle compile classpath as `compileOnly`:

   ```kotlin
   dependencies {
       compileOnly(files("libs/MobBattle.jar"))
   }
   ```

2. Declare the runtime dependency in your plugin's `manifest.json`, so Mob Battle loads first:

   ```json
   "Dependencies": {
     "Dackoro:Mob Battle": "*"
   }
   ```

3. Get the API with `MobBattlePlugin.getApi()` once Mob Battle has loaded, for example from your plugin's setup or start. It returns `null` while Mob Battle isn't loaded and after it shuts down.

Also note:
- `MobBattleApi.API_VERSION` (currently `1`) is an integer you can use to gate your integration if you support more than one API generation.
- The plugin ID is `Dackoro:Mob Battle`.
- Compile against the same Mob Battle jar you deploy, and check `API_VERSION` when you update it.

## Requests

Each start request returns a `CompletableFuture`:

```java
CompletableFuture<StartResult> startDuel(UUID worldId, UUID firstId, UUID secondId);
CompletableFuture<StartResult> startDuel(World world, UUID firstId, UUID secondId);

CompletableFuture<StartResult> startRoyale(UUID worldId, Vector3d center, double radius, int maxMobs);
CompletableFuture<StartResult> startRoyale(World world, Vector3d center, double radius, int maxMobs);

CompletableFuture<StartResult> startFocus(UUID worldId, UUID targetId, double radius, int maxMobs);
CompletableFuture<StartResult> startFocus(World world, UUID targetId, double radius, int maxMobs);

Snapshot get(UUID eventId);
CompletableFuture<Snapshot> stop(UUID eventId);
AutoCloseable subscribe(Consumer<BattleEvent> listener);
```

`World` is `com.hypixel.hytale.server.core.universe.world.World` and `Vector3d` is `org.joml.Vector3d`. The `UUID worldId` overloads look up a currently loaded world. The `World` overloads are for callers that already hold the world.

Validation:

| Request | Requirements |
|---|---|
| Duel | Two different UUIDs, both living NPCs in that world. |
| Royale | A finite center, `radius` 1–512 blocks, `maxMobs` 2–200, and at least two living NPCs in the area. |
| Focus | A living target NPC, `radius` 1–512, `maxMobs` 2–200, and at least one other living NPC within `radius` of the target. |

An invalid request completes with a rejected `StartResult`: `accepted()` is `false`, `snapshot()` is `null`, and `error()` holds a readable reason.

An accepted `StartResult` carries the event's snapshot, whose `eventId` is stable for the life of the event. The initial state can be:
- `SCHEDULED` while a countdown runs;
- `RUNNING` when the configured countdown is zero;
- `ENDED` if the start was cancelled straight away.

The same `eventId` stays attached as the event moves through `SCHEDULED`, `RUNNING` and `ENDED`.

### Threading

The future completes once the request is validated and the event or countdown is registered, not when the battle ends.

The work runs on the world thread, so:
- Accepted continuations can run inline on that thread.
- Rejected or unknown requests may complete immediately.
- Keep completion handlers short and non-blocking.
- Never wait on these futures from a world thread.

## Snapshots

`Snapshot` is an immutable record:

| Field | Type | Meaning |
|---|---|---|
| `eventId` | `UUID` | The event's ID. |
| `worldId` | `UUID` | The world it runs in (`null` for `UNKNOWN`). |
| `mode` | `Mode` | `DUEL`, `ROYALE` or `FOCUS` (`null` for `UNKNOWN`). |
| `state` | `State` | `SCHEDULED`, `RUNNING`, `ENDED` or `UNKNOWN`. |
| `winners` | `List<UUID>` | Winning NPC UUIDs (never `null`, possibly empty). |
| `winnerSide` | `Integer` | The winning side, when there is one; otherwise `null`. |
| `endReason` | `EndReason` | Set once the event has ended; otherwise `null`. |
| `updatedAtMillis` | `long` | When this snapshot was taken (epoch milliseconds). |

`get(eventId)` returns the latest snapshot. It returns an `UNKNOWN` snapshot for a `null`, unknown or expired ID, or after Mob Battle shuts down.

Record retention:
- Ended records are kept for up to 24 hours.
- The registry holds at most 4096 records. The oldest ended records may be dropped sooner once it's full.
- Active records are never dropped.
- If the registry is full of active events, a new start is rejected.

## Completion, stop and listeners

`subscribe(listener)` registers a listener for **terminal** events. Each `BattleEvent` holds the event's final `snapshot()`. Subscribe before you submit a start request, so the listener is installed before the event can finish. `subscribe` throws `IllegalStateException` if Mob Battle has shut down. Close the returned `AutoCloseable` to unsubscribe, at the latest when your plugin shuts down.

End reasons:

| `EndReason` | Meaning | Winners |
|---|---|---|
| `WIN` | One side won. | Winner UUIDs and side, when available. |
| `TIMEOUT` | The timer ran out. | Only when the configured time-up rule picks a winner (for example `health` or `judge`); otherwise none. |
| `DRAW` | Nobody won. | None. |
| `STOPPED` | Stopped through `stop()` or by an admin. | None. |
| `REPLACED` | The fighters received a new order (another battle or wand action took them). | None. |
| `UNLOAD` | A fighter or the world unloaded. | None. |
| `SHUTDOWN` | Mob Battle shut down. | None. |
| `CANCELLED_COUNTDOWN` | The countdown ended without a valid battle (for example, fighters died or became unable to fight). | None. |

Give a win-based reward only for an appropriate result with winner data. Apply your own rules to `TIMEOUT` results.

Listener rules:
- Mob Battle isolates listener exceptions, but it doesn't guarantee which thread calls the listener. Normal endings usually publish from a world thread; unload and shutdown paths may publish from another thread.
- Never block in a listener, and never change ECS state directly from it.
- To change world state, queue the work with `World.execute(...)` and check that the world is still available when it runs.
- Snapshots and event UUIDs are safe to keep. The API exposes no `World`, entity or ECS references.

`stop(eventId)` cancels a scheduled countdown or stops a running event, then completes with the terminal snapshot (`STOPPED`):
- An unknown ID returns an `UNKNOWN` snapshot.
- An event that has already ended returns its existing terminal snapshot.
- If the event's world is no longer loaded, the event ends with `UNLOAD`.

## Example

The class below starts a royale and handles its result:
- It subscribes before starting.
- It matches terminal events by ID and mode.
- It catches an event that ends before the start callback has stored its ID.
- It treats non-win endings as non-rewardable.
- It unsubscribes on close.

Compile it against the Mob Battle jar and `HytaleServer.jar`.

```java
package example;

import com.hypixel.hytale.server.core.universe.world.World;
import dev.dackoro.mobbattle.MobBattleApi;
import dev.dackoro.mobbattle.MobBattlePlugin;
import org.joml.Vector3d;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class RoyaleEvent implements AutoCloseable {
    private final Consumer<String> log;
    private final AtomicReference<UUID> eventId = new AtomicReference<>();
    private final AtomicBoolean handled = new AtomicBoolean();
    private MobBattleApi api;
    private AutoCloseable subscription;

    public RoyaleEvent(Consumer<String> log) {
        this.log = log;
    }

    /** Call after Mob Battle has loaded. */
    public void start(World world, Vector3d center) {
        api = MobBattlePlugin.getApi();
        if (api == null) {
            log.accept("Mob Battle API is not available.");
            return;
        }

        // Subscribe first, so the listener is in place before the event can end.
        subscription = api.subscribe(event -> {
            MobBattleApi.Snapshot snapshot = event.snapshot();
            UUID expected = eventId.get();
            if (expected == null || snapshot.mode() != MobBattleApi.Mode.ROYALE
                    || !expected.equals(snapshot.eventId())) {
                return;
            }
            // Listeners may run on any thread: defer world work.
            world.execute(() -> handleEnd(snapshot));
        });

        // Accepted starts complete on the world thread: keep this continuation short.
        api.startRoyale(world, new Vector3d(center), 48.0, 80).thenAccept(result -> {
            if (!result.accepted()) {
                log.accept("Royale rejected: " + result.error());
                return;
            }
            MobBattleApi.Snapshot initial = result.snapshot();
            eventId.set(initial.eventId());
            log.accept("Royale accepted as " + initial.eventId() + " (" + initial.state() + ")");
            // The event may have ended before its ID was stored above.
            MobBattleApi.Snapshot latest = api.get(initial.eventId());
            if (latest.state() == MobBattleApi.State.ENDED) {
                world.execute(() -> handleEnd(latest));
            }
        }).exceptionally(error -> {
            log.accept("Royale request failed: " + error.getClass().getSimpleName());
            return null;
        });
    }

    private void handleEnd(MobBattleApi.Snapshot snapshot) {
        UUID expected = eventId.get();
        if (expected == null || !expected.equals(snapshot.eventId())
                || snapshot.state() != MobBattleApi.State.ENDED
                || !handled.compareAndSet(false, true)) {
            return;
        }
        boolean hasWinner = !snapshot.winners().isEmpty() && snapshot.winnerSide() != null;
        boolean winResult = snapshot.endReason() == MobBattleApi.EndReason.WIN
                || snapshot.endReason() == MobBattleApi.EndReason.TIMEOUT;
        if (winResult && hasWinner) {
            // A TIMEOUT winner depends on the server's time-up rule: reward only if that fits your game.
            log.accept("Royale winners: " + snapshot.winners());
            return;
        }
        // DRAW, STOPPED, REPLACED, UNLOAD, SHUTDOWN, CANCELLED_COUNTDOWN or no winner data: no reward.
        log.accept("Royale ended without a winner: " + snapshot.endReason());
    }

    /** Call from your plugin's shutdown. */
    @Override
    public void close() throws Exception {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
```

## Not included

- **Trigger volumes.** Mob Battle has no Trigger Volumes adapter, and vanilla command execution isn't a supported way to reach this API. Hytale removed `RunCommandEffect` in Update 5, and `RunRootInteraction` needs an interaction actor that an ownerless trigger doesn't have. One way to build it would be a custom `TriggerEffect` that receives a `TriggerContext` and calls this API, with `Hytale:TriggerVolumes` declared as a dependency. It would need a per-volume cooldown or deduplication, so repeated activations don't start duplicate battles.
- **Other features.** Waves, reward hooks, per-request presets, named arenas and duplicate-event policies are outside the current API.
