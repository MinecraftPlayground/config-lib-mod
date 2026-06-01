<img src="assets/icon.png" width="64" align="right">

# Config Lib

A Fabric library for managing YAML configuration files with smart merge support — new fields are added automatically when your config class changes, without overwriting existing user values.

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

```java
import dev.loat.yaml_config_lib.annotation.Annotation;
import java.util.List;

@Annotation.Comment("""
    Main configuration file for MyMod.
    Edit values below to customize the mod's behavior.
    """)
public class MyConfig {
    private MyConfig() {}

    @Annotation.Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
    public String logLevel = "INFO";

    @Annotation.Comment("Maximum number of connections.")
    public int maxConnections = 10;

    @Annotation.Comment("Enabled features.")
    public List<String> features = List.of("chat", "alerts");

    @Annotation.Comment("Database connection settings.")
    public DatabaseConfig database = new DatabaseConfig();
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
# Main configuration file for MyMod.
# Edit values below to customize the mod's behavior.

# The log level.
# Can be DEBUG, INFO, WARN or ERROR.
# Default: 'INFO'
logLevel: INFO
# Maximum number of connections.
# Default: 10
maxConnections: 10
# Enabled features.
# Default: ['chat', 'alerts']
features:
- chat
- alerts
# Database connection settings.
database:
  # The database host.
  # Default: 'localhost'
  host: localhost
  # The database port.
  # Default: 5432
  port: 5432
```

### 2. Set up the ConfigManager

Create a `ConfigManager` instance and wrap it in a dedicated `Config` class:

```java
import dev.loat.yaml_config_lib.ConfigManager;

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

```java
MANAGER.reloadAll();
```

### 5. Sub-directories

```java
MANAGER.add("modules/chat.yml", ChatConfig.class);
// <game_dir>/config/my_mod/modules/chat.yml
```

---

## Smart merge

### New field added to the class

The missing key is inserted with its default value. All existing values are preserved. The same applies recursively to nested objects.

Before (`config.yml` on disk):
```yaml
logLevel: DEBUG
maxConnections: 10
```

After adding `features` to the class:
```yaml
logLevel: DEBUG
maxConnections: 10
# Enabled features.
# Default: ['chat', 'alerts']
features:
- chat
- alerts
```

### Field removed from the class

Orphaned keys are kept at the bottom of the file with an auto-generated comment:

```yaml
logLevel: DEBUG
# @deprecated: This key is no longer used and can be removed safely.
oldField: someValue
```

---

## Annotations

All annotations are accessed via the `Annotation` container class:

```java
import dev.loat.yaml_config_lib.annotation.Annotation;
```

### `@Annotation.Comment`

Can be placed on a **class** or a **field**.

On a **class** — written as a banner at the very top of the file:
```java
@Annotation.Comment("Main configuration file for MyMod.")
public class MyConfig { ... }
```

On a **field** — written above that key:
```java
@Annotation.Comment("The log level.")
public String logLevel = "INFO";
```

Multi-line comments are supported. Relative indentation within the text is preserved:
```java
@Annotation.Comment("""
    First line.
      Indented sub-line.
    Back to normal.
    """)
public String logLevel = "INFO";
```
Output:
```yaml
# First line.
#   Indented sub-line.
# Back to normal.
# Default: 'INFO'
logLevel: INFO
```

### `@Annotation.Deprecated`

Marks a field as deprecated while keeping it in the class for backwards compatibility:

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

## Default values

Every scalar and list field automatically gets a `# Default: <value>` comment line generated from the class-defined default. This is written once when the file is created or updated, so users always know the original value.

| Field type | Format |
|---|---|
| `String` | `# Default: 'value'` |
| `int`, `double`, `boolean`, … | `# Default: 42` |
| `List<String>` | `# Default: ['a', 'b', 'c']` |
| Nested object | *(skipped — each nested field has its own line)* |

---

## Minecraft Component support

Fields of type `net.minecraft.network.chat.Component` are supported out of the box:

```java
@Annotation.Comment("The message shown to players on join.")
public Component joinMessage = Component.literal("Welcome!");
```

---

## Constraints

- The **root config class** may have a private no-arg constructor.
- **Nested config classes** (used as field types) must have a public no-arg constructor,
  since SnakeYAML needs to instantiate them during deserialization.

---

## License

[LGPL-3.0](LICENSE)
