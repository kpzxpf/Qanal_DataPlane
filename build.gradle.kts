plugins {
    java
    application
    id("com.google.protobuf") version "0.9.4"
}

group = "com.qanal"
version = "0.1.0-SNAPSHOT"
description = "Qanal Data Plane — QUIC-based high-speed file transfer engine"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.qanal.dataplane.QanalDataPlaneMain")
}

// ── Version catalog ──
val nettyVersion = "4.1.115.Final"
val quicVersion = "0.0.66.Final"
val grpcVersion = "1.68.1"
val protobufVersion = "4.28.3"

dependencies {

    // ═══════════════════════════════════════════
    //  Netty Core
    // ═══════════════════════════════════════════
    implementation("io.netty:netty-all:$nettyVersion")

    // ═══════════════════════════════════════════
    //  Netty QUIC  (BoringSSL-based, native)
    //  Classifier picks the right native lib for OS/arch.
    //  Add more classifiers for cross-platform builds.
    // ═══════════════════════════════════════════
    implementation("io.netty.incubator:netty-incubator-codec-native-quic:$quicVersion:linux-x86_64")
    implementation("io.netty.incubator:netty-incubator-codec-native-quic:$quicVersion:linux-aarch_64")
    implementation("io.netty.incubator:netty-incubator-codec-native-quic:$quicVersion:osx-x86_64")
    implementation("io.netty.incubator:netty-incubator-codec-native-quic:$quicVersion:osx-aarch_64")

    // ═══════════════════════════════════════════
    //  gRPC Client  (Data Plane → Control Plane)
    // ═══════════════════════════════════════════
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // ═══════════════════════════════════════════
    //  Hashing & Crypto
    // ═══════════════════════════════════════════
    implementation("net.openhft:zero-allocation-hashing:0.16")  // xxHash64

    // ═══════════════════════════════════════════
    //  Configuration
    // ═══════════════════════════════════════════
    implementation("com.typesafe:config:1.4.3")                 // HOCON config (Lightbend)

    // ═══════════════════════════════════════════
    //  Logging
    // ═══════════════════════════════════════════
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // ═══════════════════════════════════════════
    //  Metrics
    // ═══════════════════════════════════════════
    implementation("io.micrometer:micrometer-core:1.14.1")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.1")

    // ═══════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // ═══════════════════════════════════════════
    //  Test
    // ═══════════════════════════════════════════
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── Protobuf / gRPC code generation ──
// Uses the same .proto from control-plane.
// In production you'd publish proto to a shared artifact.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc").apply {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDirs(
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/grpc"
            )
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ── Fat JAR for deployment ──
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.qanal.dataplane.QanalDataPlaneMain"
    }
}
