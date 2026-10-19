plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0-beta13"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://repo.faststats.dev/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")
    compileOnly("net.luckperms:api:5.5")
    implementation("dev.faststats.metrics:bukkit:0.22.0")
    implementation("redis.clients:jedis:5.2.0")
    // Chat filter: Simplified <-> Traditional Chinese conversion of filter words.
    implementation("com.github.houbb:opencc4j:1.8.1")
    // Nick subsystem storage backends (bundled unrelocated, matching the former
    // HippoNick jar — relocating a JDBC driver breaks Class.forName/driverClassName).
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        relocate("dev.faststats", "${rootProject.group}.hippoTab.libs.faststats")
        relocate("redis.clients", "net.mwtw.hippoTab.libs.redis.clients")
        relocate("org.apache.commons.pool2", "net.mwtw.hippoTab.libs.org.apache.commons.pool2")
    }

    jar {
        archiveClassifier.set("slim")
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

// ---------------------------------------------------------------- 自動上傳
// 建置完把 jar 用 SFTP（scp）傳到伺服器。連線設定寫在使用者的 ~/.gradle/gradle.properties，不進版控；
// 沒寫就不上傳，建置照常。上傳失敗也不會讓建置失敗，只會印一行提示。
//
//   formosaDeployHost=伺服器位址
//   formosaDeployUser=帳號
//   formosaDeployPath=遠端資料夾（建議填 plugins/update：伺服器下次重啟時會自動換上，不用先關服再覆蓋）
//   formosaDeployPort=22            （可省略）
//   formosaDeployKey=私鑰檔的路徑    （可省略，省略就用 ssh 預設的金鑰）
//
// 只能用金鑰登入，不支援密碼：密碼要有人在鍵盤前輸入，自動化做不到。
// 手動上傳：gradlew deploy。暫時不想上傳：加 -x deploy。
val deployHost = providers.gradleProperty("formosaDeployHost")
val deployUser = providers.gradleProperty("formosaDeployUser")
val deployPath = providers.gradleProperty("formosaDeployPath")
val deployPort = providers.gradleProperty("formosaDeployPort").orElse("22")
val deployKey = providers.gradleProperty("formosaDeployKey")

tasks.register<Exec>("deploy") {
    group = "deployment"
    description = "把建置好的 jar 上傳到伺服器"
    // 這個工作會讀使用者的連線設定並執行外部指令，不適合被設定快取記住
    notCompatibleWithConfigurationCache("uploads with scp using per-user settings")
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)
    onlyIf { deployHost.isPresent && deployUser.isPresent && deployPath.isPresent }
    isIgnoreExitValue = true
    doFirst {
        val file = jar.get().asFile
        // 在 jar 所在的資料夾執行、只給檔名：Windows 的完整路徑有「D:」，scp 會把冒號前面當成主機名稱
        workingDir = file.parentFile
        val command = mutableListOf("scp", "-P", deployPort.get(), "-o", "BatchMode=yes", "-o", "ConnectTimeout=10")
        if (deployKey.isPresent) {
            command += listOf("-i", deployKey.get())
        }
        command += listOf(file.name, "${deployUser.get()}@${deployHost.get()}:${deployPath.get().trimEnd('/')}/")
        commandLine(command)
    }
    doLast {
        val target = "${deployHost.get()}:${deployPath.get()}"
        if (executionResult.get().exitValue == 0) {
            println("已上傳 ${jar.get().asFile.name} 到 $target")
        } else {
            println("上傳到 $target 失敗（jar 本身建置成功）。請看上面 scp 的訊息")
        }
    }
}

tasks.build {
    finalizedBy("deploy")
}
