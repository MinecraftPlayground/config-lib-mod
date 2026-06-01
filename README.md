<img src="assets/icon.png" width="64" align="right">

# Config Lib

A Fabric library for managing YAML configuration files with smart merge support - new fields are added automatically when your config class changes, without overwriting existing user values.

## Installation

Add the following to your `build.gradle`:

```groovy
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.MinecraftPlayground:config-lib:VERSION")
}
```

And declare the dependency in your `fabric.mod.json`:

```json
"depends": {
    "config-lib": "*"
}
```

---

## Usage

### 1. Define your config classes

A config class is a plain Java class with public instance fields. Default values are written to the file when it is first created.

Use `@Annotation.Comment` to add a description above a field in the YAML output.
Nested objects are fully supported — annotations on their fields are picked up automatically.

```java
import dev.loat.config_lib.annotation.Annotation;

public class MyConfig {

    @Annotation.Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
    public String logLevel = "INFO";

    @Annotation.Comment("Maximum number of connections.")
    public int maxConnections = 10;

    @Annotation.Comment("Database connection settings.")
    public DatabaseConfig database = new DatabaseConfig();

    public boolean featureEnabled = false;
}
```

```java
public class DatabaseConfig {

    @Annotation.Comment("The database host.")
    public String host = "localhost";

    @Annotation.Comment("The database port.")
    public int port = 5432;
}
```

Output:
```yaml
# The log level.
# Can be DEBUG, INFO, WARN or ERROR.
logLevel: INFO
# Maximum number of connections.
maxConnections: 10
# Database connection settings.
database:
  # The database host.
  host: localhost
  # The database port.
  port: 5432
featureEnabled: false
```

### 2. Set up the ConfigManager

Create a `ConfigManager` instance with your root directory and register your config files.
The recommended pattern is a dedicated `Config` class per mod:

```java
import dev.loat.config_lib.ConfigManager;

public final class Config {
    private Config() {}

    private static final ConfigManager MANAGER = new ConfigManager("my_mod");

    public static void register() {
        MANAGER.add("config.yml", MyConfig.class);
    }

    public static MyConfig get() {
        return MANAGER.get(MyConfig.class);
    }
}
```

Call `Config.register()` in `onInitialize()`:

```java
public class MyMod implements ModInitializer {

    @Override
    public void onInitialize() {
        Config.register();
    }
}
```

### 3. Read values

```java
String level = Config.get().logLevel;
int max = Config.get().maxConnections;
String host = Config.get().database.host;
```

### 4. Reload

To reload all registered config files from disk at runtime (e.g. via a command):

```java
MANAGER.reloadAll();
```

### 5. Sub-directories

Sub-directories inside the root are supported:

```java
MANAGER.add("modules/chat.yml", ChatConfig.class);
// <game_dir>/config/my_mod/modules/chat.yml
```

---

## Smart merge

The library automatically keeps config files in sync with your config class.

### New field added to the class

When a field is added to the class, the missing key is inserted with its default value. All existing values are preserved.

Before (`config.yml` on disk):
```yaml
logLevel: DEBUG
maxConnections: 10
```

After adding `featureEnabled` to the class and restarting:
```yaml
logLevel: DEBUG
maxConnections: 10
featureEnabled: false
```

The same applies to nested objects — only the missing nested keys are added.

### Field removed from the class

Keys that no longer exist in the class are kept in the file and marked with a comment so users know they can be removed:

```yaml
logLevel: DEBUG
maxConnections: 10
# @deprecated: This key is no longer used and can be removed safely.
oldField: someValue
```

---

## Annotations

All annotations are accessed via the `Annotation` container class:

```java
import dev.loat.config_lib.annotation.Annotation;
```

### `@Annotation.Comment`

Adds a description above the field in the YAML file. Multi-line comments are supported via `\n`.

```java
@Annotation.Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
public String logLevel = "INFO";
```

Output:
```yaml
# The log level.
# Can be DEBUG, INFO, WARN or ERROR.
logLevel: INFO
```

### `@Annotation.Deprecated`

Marks a field as deprecated while keeping it in the class for backwards compatibility.
A warning comment is written above the field in the YAML file.

```java
@Annotation.Deprecated(migratedTo = "logLevel", removedIn = "2.0.0")
public String log_level = "INFO";

public String logLevel = "INFO";
```

Output:
```yaml
# @deprecated: Migrated to 'logLevel'. Will be removed in version 2.0.0.
log_level: INFO

logLevel: INFO
```

| Field | Description |
|---|---|
| `migratedTo` | The new key this field was replaced by |
| `removedIn` | The version in which this field will be removed |
| `message` | Custom message — if set, `migratedTo` and `removedIn` are ignored |

---

## Minecraft Component support

Fields of type `net.minecraft.network.chat.Component` are supported out of the box
and are serialized using Minecraft's own `ComponentSerialization` codec.

```java
@Annotation.Comment("The message shown to players on join.")
public Component joinMessage = Component.literal("Welcome!");
```

Output:
```yaml
# The message shown to players on join.
joinMessage: Welcome!
```

---

## Constraints

- The root config class may have a **private** no-arg constructor.
- Nested config classes (used as field types) must have a **public** no-arg constructor,
  since SnakeYAML needs to instantiate them during deserialization.

---

## License

[LGPL-3.0](LICENSE)
