plugins {
    id("zenithproxy.plugin.dev") version "1.2.+"
}

group = property("maven_group") as String
version = property("plugin_version") as String
val mc = property("mc") as String
val pluginId = property("plugin_id") as String

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    buildConstants {
        fields = mapOf(
            "VERSION" to project.version as String,
            "MC_VERSION" to mc,
            "PLUGIN_ID" to pluginId,
            "MAVEN_GROUP" to group as String,
        )
    }
    // the minimum supported java version for users of your plugin
    javaReleaseVersion = JavaLanguageVersion.of(21)
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases and Dependencies"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")
}
