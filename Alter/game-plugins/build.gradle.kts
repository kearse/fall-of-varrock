description = "Alter Servers Plugins"
val lib = rootProject.project.libs

dependencies {
    implementation(projects.gameServer)
    implementation(projects.util)
    implementation(project(":game-api"))
    implementation(rootProject.project.libs.rsprot)
    implementation(rootProject.projects.plugins.filestore)
    implementation(rootProject.projects.plugins.rscm)
    implementation(lib.routefinder)
    implementation(lib.mongo.bson)
    implementation(lib.mongo.driver)
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
// Economy arbitrage auditor (Team 2). Boots the cache + item overrides offline, constructs the shop
// and recipe plugins, snapshots every shop, and searches the value graph for NPC loops that mint gp.
//   gradlew :game-plugins:economyAudit "-PeconCache=C:\path\to\Alter\data\cache"
//   gradlew :game-plugins:economyAudit -PeconMode=selftest "-PeconCache=..."
// One -P property per argument (NOT a space-split string): the cache path contains spaces on the dev box.
tasks.register<JavaExec>("economyAudit") {
    description = "Economy arbitrage auditor -> docs/economy-arbitrage-audit.{md,json}"
    group = "application"
    // Same relative-path contract as the live server and :game-server:metaReqCheck: ../data/cfg/** resolves from the module dir.
    workingDir = projectDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.plugins.content.economy.audit.EconomyAuditToolKt")
    args = listOfNotNull(
        "--cache=" + ((project.findProperty("econCache") as String?) ?: "../data/cache"),
        "--out=" + ((project.findProperty("econOut") as String?) ?: "../../docs"),
        "--mode=" + ((project.findProperty("econMode") as String?) ?: "audit"),
        (project.findProperty("econPegs") as String?)?.let { "--pegs=$it" },
        (project.findProperty("econFlags") as String?)?.let { "--flags=$it" },
    )
    jvmArgs = listOf("-Xmx3g")
    isIgnoreExitValue = true
}
