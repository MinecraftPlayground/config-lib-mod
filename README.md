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

## Usage

### 1. Define your config class

A config class is a plain Java class with public fields. Default values are written to the file if it does not exist yet.

Use `@Comment` to add a description above a field in the YAML file.

```java
public class MyConfig {

    @Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
    public String logLevel = "INFO";

    @Comment("Maximum number of connections.")
    public int maxConnections = 10;

    public boolean featureEnabled = false;
}
```

### 2. Register and load

Call `ConfigManager.root()` and `ConfigManager.add()` in `onInitialize()`:

```java
public class MyMod implements ModInitializer {

    @Override
    public void onInitialize() {
        ConfigManager.root("my_mod");
        ConfigManager.add("config.yml", MyConfig.class);
    }
}
```

All config files are resolved relative to `<game_dir>/config/<root>/`. Sub-directories are supported:

```java
ConfigManager.add("modules/chat.yml", ChatConfig.class);
// <game_dir>/config/my_mod/modules/chat.yml
```

### 3. Read values

```java
String level = ConfigManager.get(MyConfig.class).logLevel;
int max = ConfigManager.get(MyConfig.class).maxConnections;
```

### 4. Reload

To reload all config files from disk at runtime (ex. via a command):

```java
ConfigManager.reloadAll();
```

---

## Smart merge

The library automatically keeps config files in sync with your config class.

### New field added to the class

When a field is added to the class, the key is added to the existing file with its default value. All other values are preserved.

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

### Field removed from the class

Keys that no longer exist in the class are kept in the file and marked with a comment so the user knows they can be removed:

```yaml
logLevel: DEBUG
maxConnections: 10
# @deprecated: This key is no longer used and can be removed safely.
oldField: someValue
```

---

## Annotations

### `@Comment`

Adds a description above the field in the YAML file.

```java
@Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
public String logLevel = "INFO";
```

Output:
```yaml
# The log level.
# Can be DEBUG, INFO, WARN or ERROR.
logLevel: INFO
```

### `@ConfigDeprecated`

Marks a field as deprecated while keeping it in the class for backwards compatibility. A warning comment is written above the field in the YAML file.

```java
@ConfigDeprecated(migratedTo = "logLevel", removedIn = "2.0.0")
public String log_level = "INFO";

public String logLevel = "INFO";
```

Output:
```yaml
# @deprecated: Migrated to 'logLevel'. Will be removed in version 2.0.0.
log_level: INFO

logLevel: INFO
```

All fields of `@ConfigDeprecated`:

| Field | Description |
|---|---|
| `migratedTo` | The new key this field was replaced by |
| `removedIn` | The version in which this field will be removed |
| `message` | Custom message — overrides `migratedTo` and `removedIn` |

---

## Minecraft Component support

Fields of type `net.minecraft.network.chat.Component` are supported out of the box and are serialized using Minecraft's own `ComponentSerialization` codec.

```java
public class MyConfig {

    @Comment("The message shown to players on join.")
    public Component joinMessage = Component.literal("Welcome!");
}
```

Output:
```yaml
# The message shown to players on join.
joinMessage: Welcome!
```

---

## License

[LGPL-3.0](LICENSE)
