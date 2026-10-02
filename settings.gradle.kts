pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "original-rpg-server"

include(
    "plugins:rpg-api",
    "plugins:rpg-core",
    "plugins:rpg-classes",
    "plugins:rpg-items",
    "plugins:rpg-combat",
    "plugins:rpg-mobs",
    "plugins:rpg-dungeons",
    "plugins:rpg-enhancement",
    "plugins:rpg-market",
    "plugins:rpg-skills",
    "plugins:rpg-sulfur",
    "plugins:rpg-world",
    "plugins:rpg-economy",
    "plugins:rpg-commerce",
    "plugins:rpg-admin",
    "plugins:rpg-guide",
    "plugins:rpg-hub",
    "plugins:rpg-integrations",
    "plugins:rpg-performance",
    "plugins:rpg-cosmetics",
    "plugins:rpg-ai-npc",
    "plugins:rpg-story",
    "plugins:rpg-community",
    "plugins:rpg-essentials",
    "plugins:rpg-pets",
    "tools:db-migration"
)
