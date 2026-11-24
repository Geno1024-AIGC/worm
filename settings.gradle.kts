pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Same shape as the old crawlers repo: root `worm`, child project `worm`, so
// module paths spell exactly :worm:core, :worm:qzone:shuoshuo, ...
rootProject.name = "worm"

include(":worm:core")
include(":worm:qzone:common")
include(":worm:qzone:shuoshuo")
include(":worm:qzone:album")
include(":worm:zhihu")
include(":worm:android")
