plugins {
    alias(libs.plugins.shadow)
    application
    `maven-publish`
}
description = "Alter Game Server Launcher"
application {
    apply(plugin = "maven-publish")
    mainClass.set("org.alter.game.Launcher")
    // Server-runtime JVM tuning. Previously the `run` task launched with NO heap/GC flags, so a
    // GC pause could blow the 600ms game-tick budget and stall the world (visible as freezing).
    // Pin the heap (no resize churn) and use G1 with a short pause target so collections stay
    // well under one tick. Bump -Xmx if peak pop/players grow.
    applicationDefaultJvmArgs = listOf(
        "-Xms2g",
        "-Xmx4g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=100",
        "-XX:+ParallelRefProcEnabled",
    )
}
val lib = rootProject.project.libs
dependencies {
    with(lib) {
        implementation(projects.util)
        runtimeOnly(projects.gamePlugins)
        implementation(kotlinx.coroutines)
        implementation(reflection)
        implementation(commons)
        implementation(classgraph)
        implementation(fastutil)
        implementation(bouncycastle)
        implementation(jackson.module.kotlin)
        implementation(jackson.dataformat.yaml)
        implementation(kotlin.csv)
        implementation(mongo.bson)
        implementation(mongo.driver)
        implementation(rootProject.projects.plugins.rscm)
        testImplementation(junit)
        implementation(rootProject.project.libs.rsprot)
        implementation(rootProject.projects.plugins.filestore)
        implementation(rootProject.projects.plugins.rscm)
        implementation(rootProject.projects.plugins.tools)
        implementation(lib.routefinder)
        implementation("com.displee:rs-cache-library:7.1.3") // writable cache lib for authoring the siege HUD interface
    }
}
sourceSets {
    named("main") {
        kotlin.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
}

@Suppress("ktlint:standard:max-line-length")
tasks.register("install") {
    description = "Install Alter"
    val cacheList =
        listOf(
            "/cache/main_file_cache.dat2",
            "/cache/main_file_cache.idx0",
            "/cache/main_file_cache.idx1",
            "/cache/main_file_cache.idx2",
            "/cache/main_file_cache.idx3",
            "/cache/main_file_cache.idx4",
            "/cache/main_file_cache.idx5",
            "/cache/main_file_cache.idx7",
            "/cache/main_file_cache.idx8",
            "/cache/main_file_cache.idx9",
            "/cache/main_file_cache.idx10",
            "/cache/main_file_cache.idx11",
            "/cache/main_file_cache.idx12",
            "/cache/main_file_cache.idx13",
            "/cache/main_file_cache.idx14",
            "/cache/main_file_cache.idx15",
            "/cache/main_file_cache.idx17",
            "/cache/main_file_cache.idx18",
            "/cache/main_file_cache.idx19",
            "/cache/main_file_cache.idx20",
            "/cache/main_file_cache.idx255",
            "xteas.json",
        )
    cacheList.forEach {
        val file = File("${rootProject.projectDir}/data/$it")
        if (!file.exists()) {
            throw GradleException(
                "\u001B[45m \u001B[30m Missing file! : $file. Go back to: https://github.com/AlterRSPS/Alter and read how to setup plz >____> It's so easy to set this up and you failed at it wtfff?!?!. \u001B[0m",
            )
        }
    }
    dependsOn("runRsaService")
    dependsOn("decryptMap")

    doLast {
        copy {
            into("${rootProject.projectDir}/")
            from("${rootProject.projectDir}/game.example.yml") {
                rename("game.example.yml", "game.yml")
            }
            from("${rootProject.projectDir}/dev-settings.example.yml") {
                rename("dev-settings.example.yml", "dev-settings.yml")
            }
            file("${rootProject.projectDir}/first-launch").createNewFile()
        }
    }
}
tasks.register<JavaExec>("runRsaService") {
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.game.service.rsa.RsaService")
    args = listOf("16", "1024", "./data/rsa/key.pem") // radix, bitcount, rsa pem file
}
tasks.register<JavaExec>("decryptMap") {
    description = "Will decrypt world map and remove xteas"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.game.service.mapdecrypter.decryptMap")
}
tasks.register<JavaExec>("siegeHud") {
    description = "Siege HUD cache tool (validate the if3 codec / author the interface)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.siegehud.SiegeHudCacheToolKt")
    args = ((project.findProperty("siegeArgs") as String?) ?: "validate").split(" ")
}
tasks.register<JavaExec>("teleportIface") {
    description = "Teleport portal cache tool (author the tabbed teleport interface)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.teleport.TeleportInterfaceCacheToolKt")
    args = ((project.findProperty("teleArgs") as String?) ?: "inspect").split(" ")
}
tasks.register<JavaExec>("panelIface") {
    description = "Reusable tabbed-panel cache tool (author the generic panel interface)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.panel.PanelCacheToolKt")
    args = ((project.findProperty("panelArgs") as String?) ?: "inspect").split(" ")
}
tasks.register<JavaExec>("duelRulesIface") {
    description = "Duel Arena rules-grid cache tool (author the clickable duel rules interface)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.duelrules.DuelRulesCacheToolKt")
    args = ((project.findProperty("duelRulesArgs") as String?) ?: "inspect").split(" ")
}
tasks.register<JavaExec>("minimapIcons") {
    description = "Minimap declutter tool: hide AreaType map-element icons from the minimap"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.minimap.MinimapIconsToolKt")
    args = ((project.findProperty("minimapArgs") as String?) ?: "scan").split(" ")
}
tasks.register<JavaExec>("headIcons") {
    description = "Head-icon cache tool: repaint the loot-key overhead icons to keys-only (erase the skull)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.headicons.HeadIconsToolKt")
    args = ((project.findProperty("headArgs") as String?) ?: "inspect").split(" ")
}
tasks.register<JavaExec>("mapDump") {
    description = "Dump terrain collision + objects per region (ASCII grids, PNGs, stitched overview) for offline map understanding"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.mapdump.MapDumpToolKt")
    args = ((project.findProperty("mapArgs") as String?) ?: "home").split(" ")
}
tasks.register<JavaExec>("npcDef") {
    description = "NPC def cache tool: edit an npc's cache definition (e.g. custom right-click options)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.npcdef.NpcDefToolKt")
    args = ((project.findProperty("npcArgs") as String?) ?: "inspect 1755").split(" ")
}
tasks.register<JavaExec>("itemDef") {
    description = "Item def cache tool: rename an item in the cache (client renders names from ITS cache — server YAML name overrides don't show in-game)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.itemdef.ItemDefToolKt")
    args = ((project.findProperty("itemDefArgs") as String?) ?: "inspect 4067 8851").split(" ")
}
tasks.register<JavaExec>("questTable") {
    description = "Quest tab cache tool: relabel reused OSRS quest rows to Fall of Varrock quests (docs/quest-tab-handoff.md)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.quests.QuestTablePatchKt")
    args = ((project.findProperty("questArgs") as String?) ?: "inspect").split(" ")
}
tasks.register<JavaExec>("shortcutScan") {
    description = "Scan the cache for agility-shortcut objects (by action verb) and all their world placements; 'audit' mode checks bound shortcuts land on connected ground"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.shortcutscan.ShortcutScanToolKt")
    args = ((project.findProperty("scanArgs") as String?) ?: "scan").split(" ")
}
tasks.register<JavaExec>("itemCheck") {
    description = "Print name/stacks/stackable/tradeable for given item ids (currency stackability audit)"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.itemcheck.ItemStackCheckToolKt")
    args = ((project.findProperty("itemArgs") as String?) ?: "619 621").split(" ")
}
tasks.register<JavaExec>("metaReqCheck") {
    description = "Run the real ItemMetadataService.loadAll() and print resulting skillReqs for given item ids"
    group = "application"
    workingDir = projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.itemcheck.MetaReqCheckToolKt")
    args = ((project.findProperty("itemArgs") as String?) ?: "1127 1333").split(" ")
}
tasks.register<JavaExec>("varbitCheck") {
    description = "Print a varbit's def, or list every varbit stored inside a varp"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.itemcheck.VarbitCheckToolKt")
    args = ((project.findProperty("varbitArgs") as String?) ?: "varp 1299").split(" ")
}
tasks.register<JavaExec>("objCheck") {
    description = "Print name/size/actions for given object (loc) ids"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.objcheck.ObjCheckToolKt")
    args = ((project.findProperty("objArgs") as String?) ?: "43468").split(" ")
}
tasks.register<JavaExec>("locEdit") {
    description = "Loc editor: rewrite a region's scenery from an edit-list (fallen-city POC). verify|preview|apply|restore"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.locedit.LocEditorToolKt")
    args = ((project.findProperty("locArgs") as String?) ?: "verify 12598").split(" ")
}

tasks.register<JavaExec>("terrainEdit") {
    description = "Terrain fixer: copy a tile's terrain over a void/black tile. verify|apply|restore"
    group = "application"
    workingDir = rootProject.projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.tools.terrainedit.TerrainEditToolKt")
    args = ((project.findProperty("terrainArgs") as String?) ?: "verify 12850").split(" ")
}

task<Copy>("extractDependencies") {
    from(zipTree("build/distributions/game-server-${project.version}.zip")) {
        include("game-${project.version}/lib/*")
        eachFile {
            path = name
        }
        includeEmptyDirs = false
    }
    into("build/deps")
}

tasks.register<Copy>("applicationDistribution") {
    from("$rootDir/data/") {
        into("bin/data/")
        include("**")
        exclude("saves/*")
    }
}
tasks.named<Copy>("applicationDistribution") {
    from("$rootDir") {
        into("bin")
        include("/game-plugins/*")
        include("game.example.yml")
        rename("game.example.yml", "game.yml")
    }
}
tasks.named<Zip>("shadowDistZip") {
    from("$rootDir/data/") {
        into("game-shadow-${project.version}/bin/data/")
        include("**")
        exclude("saves/*")
    }
    from("$rootDir") {
        into("game-shadow-${project.version}/bin/")
        include("/game-plugins/*")
        include("game.example.yml")
        rename("game.example.yml", "game.yml")
    }
}
tasks.register<Tar>("myShadowDistTar") {
    archiveFileName.set("game-shadow-${project.version}.tar")
    destinationDirectory.set(file("build/distributions/"))
    from("$rootDir/data/") {
        into("game-shadow-${project.version}/bin/data/")
        include("**")
        exclude("saves/*")
    }
    from("$rootDir") {
        into("game-shadow-${project.version}/bin/")
        include("/game-plugins/*")
        include("game.example.yml")
        rename("game.example.yml", "game.yml")
    }
}
tasks.named("build") {
    finalizedBy("extractDependencies")
}
tasks.named("install") {
    dependsOn("build")
}
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}


/**
 * @TODO Forgot about this one.
 */
publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
        groupId = "org.alter"
        artifactId = "alter"
        pom {
            packaging = "jar"
            name.set("Alter")
            description.set("AlterServer All")
        }
    }
}